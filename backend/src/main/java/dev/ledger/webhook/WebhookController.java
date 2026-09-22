package dev.ledger.webhook;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks")
public class WebhookController {
    private final WebhookService endpoints;
    private final DeliveryStore deliveries;

    public WebhookController(WebhookService endpoints, DeliveryStore deliveries) {
        this.endpoints = endpoints;
        this.deliveries = deliveries;
    }

    @PostMapping("/endpoints")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Endpoint registered; signing secret is returned only once")
    public ResponseEntity<WebhookService.RegisteredEndpoint> register(@Valid @RequestBody Registration request) {
        var endpoint = endpoints.register(request.accountId(), request.url());
        return ResponseEntity.created(URI.create("/api/webhooks/endpoints")).body(endpoint);
    }

    @GetMapping("/endpoints")
    public List<WebhookService.Endpoint> endpoints(@RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return endpoints.list(page, size);
    }

    @GetMapping("/deliveries")
    public List<DeliveryStore.Delivery> deliveries(@RequestParam(required = false) UUID endpointId,
            @RequestParam(required = false) @Pattern(regexp = "PENDING|PROCESSING|RETRY|DELIVERED|DEAD_LETTER") String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return deliveries.list(endpointId, status, page, size);
    }

    @GetMapping("/deliveries/{id}")
    public DeliveryStore.DeliveryDetail delivery(@PathVariable UUID id) {
        return deliveries.get(id);
    }

    @PostMapping("/deliveries/{id}/replay")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Terminal delivery queued again with its original event ID")
    public ResponseEntity<DeliveryStore.Delivery> replay(@PathVariable UUID id) {
        return ResponseEntity.accepted().body(deliveries.replay(id));
    }

    public record Registration(@NotNull UUID accountId, @NotBlank @Size(max = 2048) String url) {}
}
