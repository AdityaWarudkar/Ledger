package dev.ledger.api;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class TransferMetricsFilter extends OncePerRequestFilter {
    private final MeterRegistry registry;

    public TransferMetricsFilter(MeterRegistry registry) { this.registry = registry; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getMethod().equals("POST") || !request.getServletPath().equals("/api/transfers");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var sample = Timer.start(registry);
        boolean escaped = true;
        try {
            chain.doFilter(request, response);
            escaped = false;
        } finally {
            String outcome = escaped || response.getStatus() >= 500 ? "error"
                    : response.getStatus() >= 400 ? "rejected" : "completed";
            sample.stop(registry.timer("ledger.transfer.requests", "outcome", outcome));
            if ("true".equals(response.getHeader("Idempotency-Replayed"))) {
                registry.counter("ledger.idempotency.replays", "outcome", outcome).increment();
            }
        }
    }
}
