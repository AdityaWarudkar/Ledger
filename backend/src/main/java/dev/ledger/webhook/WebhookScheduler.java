package dev.ledger.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "ledger.webhooks.worker-enabled", havingValue = "true", matchIfMissing = true)
public class WebhookScheduler {
    private final WebhookWorker worker;

    public WebhookScheduler(WebhookWorker worker) {
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${ledger.webhooks.poll-delay-ms:1000}")
    public void deliverDueEvents() {
        for (int i = 0; i < 10 && !Thread.currentThread().isInterrupted(); i++) {
            if (!worker.processOne()) {
                break;
            }
        }
    }
}
