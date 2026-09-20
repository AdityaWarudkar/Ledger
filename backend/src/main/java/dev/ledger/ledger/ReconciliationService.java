package dev.ledger.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {
    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // Both queries must see the same committed state while transfers continue posting.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReconciliationResult check() {
        var mismatches = jdbc.query("""
                SELECT a.id, a.currency, a.balance_minor, coalesce(sum(e.amount_minor), 0) AS ledger_balance
                  FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id
                 GROUP BY a.id
                HAVING a.balance_minor <> coalesce(sum(e.amount_minor), 0)
                 ORDER BY a.id
                """, (row, index) -> new BalanceMismatch(row.getObject("id", UUID.class),
                row.getString("currency"), row.getString("balance_minor"),
                row.getBigDecimal("ledger_balance").toPlainString()));
        var currencies = jdbc.query("""
                SELECT a.currency, count(e.id) AS entry_count, coalesce(sum(e.amount_minor), 0) AS net_minor
                  FROM accounts a LEFT JOIN ledger_entries e ON e.account_id = a.id
                 GROUP BY a.currency ORDER BY a.currency
                """, (row, index) -> new CurrencyTotal(row.getString("currency"), row.getLong("entry_count"),
                row.getBigDecimal("net_minor").toPlainString()));
        boolean balanced = mismatches.isEmpty() && currencies.stream()
                .allMatch(total -> new BigDecimal(total.netMinor()).signum() == 0);
        return new ReconciliationResult(balanced, Instant.now(), currencies, mismatches);
    }

    public record BalanceMismatch(UUID accountId, String currency, String balanceMinor, String ledgerBalanceMinor) {}
    public record CurrencyTotal(String currency, long entryCount, String netMinor) {}
    public record ReconciliationResult(boolean balanced, Instant checkedAt, List<CurrencyTotal> currencies,
                                       List<BalanceMismatch> mismatches) {}
}
