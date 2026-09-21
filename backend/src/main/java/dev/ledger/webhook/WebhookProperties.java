package dev.ledger.webhook;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("ledger.webhooks")
public record WebhookProperties(
        @DefaultValue("") List<String> allowedHosts,
        @DefaultValue("false") boolean allowHttp,
        @DefaultValue("2s") Duration requestTimeout,
        @DefaultValue("30s") Duration leaseDuration,
        @DefaultValue("2s") Duration baseBackoff,
        @DefaultValue("5m") Duration maxBackoff,
        @DefaultValue("5") int maxAttempts) {
    public WebhookProperties {
        if (requestTimeout.toMillis() < 1 || leaseDuration.compareTo(requestTimeout.plusSeconds(1)) <= 0
                || baseBackoff.toMillis() < 1 || maxBackoff.compareTo(baseBackoff) < 0
                || maxAttempts < 1 || maxAttempts > 20) {
            throw new IllegalArgumentException("Invalid webhook timeout, lease, backoff, or attempt configuration");
        }
    }
}
