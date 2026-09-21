package dev.ledger.webhook;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class WebhookUrlPolicy {
    private final WebhookProperties properties;

    public WebhookUrlPolicy(WebhookProperties properties) {
        this.properties = properties;
    }

    public URI validate(String url) {
        try {
            URI uri = URI.create(url);
            boolean schemeAllowed = "https".equalsIgnoreCase(uri.getScheme())
                    || (properties.allowHttp() && "http".equalsIgnoreCase(uri.getScheme()));
            if (!schemeAllowed || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535
                    || properties.allowedHosts().stream().noneMatch(host -> host.equalsIgnoreCase(uri.getHost()))) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new WebhookException(HttpStatus.BAD_REQUEST,
                    "Webhook URL must use an allowed host and HTTPS (HTTP is enabled only for local development)");
        }
    }
}
