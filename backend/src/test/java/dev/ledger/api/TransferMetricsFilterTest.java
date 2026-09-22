package dev.ledger.api;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.*;

class TransferMetricsFilterTest {
    @Test
    void recordsCompletedAndReplayedResponsesWithoutHighCardinalityTags() throws Exception {
        var registry = new SimpleMeterRegistry();
        var filter = new TransferMetricsFilter(registry);
        for (boolean replay : new boolean[]{false, true}) {
            filter.doFilter(request(), new MockHttpServletResponse(), (request, response) -> {
                var http = (jakarta.servlet.http.HttpServletResponse) response;
                http.setStatus(201);
                http.setHeader("Idempotency-Replayed", Boolean.toString(replay));
            });
        }
        assertThat(registry.get("ledger.transfer.requests").tag("outcome", "completed").timer().count()).isEqualTo(2);
        assertThat(registry.get("ledger.idempotency.replays").tag("outcome", "completed").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).hasSize(1));
    }

    @Test
    void recordsRejectionAndEscapedFailureAndIgnoresReads() throws Exception {
        var registry = new SimpleMeterRegistry();
        var filter = new TransferMetricsFilter(registry);
        filter.doFilter(request(), new MockHttpServletResponse(), (request, response) ->
                ((jakarta.servlet.http.HttpServletResponse) response).setStatus(422));
        assertThatThrownBy(() -> filter.doFilter(request(), new MockHttpServletResponse(), (request, response) -> {
            throw new ServletException("database failure");
        })).isInstanceOf(ServletException.class);
        var read = request();
        read.setMethod("GET");
        filter.doFilter(read, new MockHttpServletResponse(), (request, response) -> {});
        assertThat(registry.get("ledger.transfer.requests").tag("outcome", "rejected").timer().count()).isEqualTo(1);
        assertThat(registry.get("ledger.transfer.requests").tag("outcome", "error").timer().count()).isEqualTo(1);
        assertThat(registry.find("ledger.idempotency.replays").counter()).isNull();
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/transfers");
        request.setServletPath("/api/transfers");
        return request;
    }
}
