package dev.ledger.webhook;

import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

@Component
public class WebhookWorker {
    private final DeliveryStore deliveries;
    private final WebhookProperties properties;
    private final WebhookUrlPolicy urls;
    private final HttpClient client;
    private final MeterRegistry metrics;

    public WebhookWorker(DeliveryStore deliveries, WebhookProperties properties, WebhookUrlPolicy urls, MeterRegistry metrics) {
        this.deliveries = deliveries;
        this.properties = properties;
        this.urls = urls;
        this.metrics = metrics;
        this.client = HttpClient.newBuilder().connectTimeout(properties.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public boolean processOne() {
        var claimed = deliveries.claim();
        if (claimed.isEmpty()) {
            return false;
        }
        var claim = claimed.get();
        long started = System.nanoTime();
        Integer status = null;
        String error = null;
        try {
            long timestamp = Instant.now().getEpochSecond();
            var request = HttpRequest.newBuilder(urls.validate(claim.url())).timeout(properties.requestTimeout())
                    .header("Content-Type", "application/json")
                    .header("X-Ledger-Event-Id", claim.eventId().toString())
                    .header("X-Ledger-Endpoint-Id", claim.endpointId().toString())
                    .header("X-Ledger-Timestamp", Long.toString(timestamp))
                    .header("X-Ledger-Signature", WebhookSignature.sign(claim.secret(), timestamp, claim.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(claim.payload())).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                status = response.statusCode();
            }
            if (status < 200 || status >= 300) {
                error = "Receiver returned HTTP " + status;
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            error = "Delivery interrupted; receiver outcome unknown";
        } catch (IOException exception) {
            error = exception.getClass().getSimpleName() + ": delivery failed; receiver outcome may be unknown";
        } catch (WebhookException exception) {
            error = exception.getMessage();
        }
        long latency = (System.nanoTime() - started) / 1_000_000;
        boolean recorded = deliveries.finish(claim, status, latency, error, backoffMillis(claim.cycleAttempts()));
        // A stale lease must not report a second delivery. finish returns after its transaction commits.
        if (recorded) {
            boolean success = status != null && status >= 200 && status < 300;
            metrics.counter("ledger.webhook.attempts", "outcome", success ? "success" : "failure").increment();
            if (!success && claim.cycleAttempts() < properties.maxAttempts()) {
                metrics.counter("ledger.webhook.retries").increment();
            }
            if (!success && claim.cycleAttempts() >= properties.maxAttempts()) {
                metrics.counter("ledger.webhook.dead.letters").increment();
            }
        }
        return true;
    }

    long backoffMillis(int attempt) {
        long cap = properties.baseBackoff().toMillis();
        long maximum = properties.maxBackoff().toMillis();
        for (int i = 1; i < attempt && cap < maximum; i++) {
            cap = cap > maximum / 2 ? maximum : cap * 2;
        }
        // Equal jitter prevents immediate retry bursts while keeping exponential growth bounded.
        long half = Math.max(1, cap / 2);
        return half + ThreadLocalRandom.current().nextLong(Math.max(1, cap - half + 1));
    }

    @PreDestroy
    public void close() {
        client.shutdownNow();
    }
}
