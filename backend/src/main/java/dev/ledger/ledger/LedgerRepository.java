package dev.ledger.ledger;

import dev.ledger.transfer.Transfer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerRepository {
    private static final String ENTRY_QUERY = """
            SELECT e.*, counterparty.id AS counterparty_id, counterparty.name AS counterparty_name
              FROM (%s) e
              JOIN transfers t ON t.id = e.transfer_id
              JOIN accounts counterparty ON counterparty.id =
                   CASE WHEN e.account_id = t.from_account_id THEN t.to_account_id ELSE t.from_account_id END
            """;
    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void post(Transfer transfer) {
        jdbc.update("""
                INSERT INTO ledger_entries (transfer_id, account_id, currency, amount_minor, created_at)
                VALUES (?, ?, ?, ?, ?), (?, ?, ?, ?, ?)
                """, transfer.getId(), transfer.getFromAccountId(), transfer.getCurrency(), -transfer.getAmountMinor(),
                Timestamp.from(transfer.getCreatedAt()),
                transfer.getId(), transfer.getToAccountId(), transfer.getCurrency(), transfer.getAmountMinor(),
                Timestamp.from(transfer.getCreatedAt()));
    }

    public List<LedgerEntryResponse> forAccount(UUID accountId, int page, int size) {
        // Compute the running balance over the full account history before slicing the page.
        String query = ENTRY_QUERY.formatted("""
                SELECT *, sum(amount_minor) OVER (ORDER BY id) AS running_balance_minor
                  FROM ledger_entries WHERE account_id = ?
                """) + " ORDER BY e.id DESC LIMIT ? OFFSET ?";
        return jdbc.query(query, this::entry, accountId, size, (long) page * size);
    }

    public long countForAccount(UUID accountId) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entries WHERE account_id = ?", Long.class, accountId);
    }

    public List<LedgerEntryResponse> forTransfer(UUID transferId) {
        String query = ENTRY_QUERY.formatted("""
                SELECT *, sum(amount_minor) OVER (PARTITION BY account_id ORDER BY id) AS running_balance_minor
                  FROM ledger_entries WHERE account_id IN (
                       SELECT from_account_id FROM transfers WHERE id = ?
                       UNION SELECT to_account_id FROM transfers WHERE id = ?)
                """) + " WHERE e.transfer_id = ? ORDER BY e.id";
        return jdbc.query(query, this::entry, transferId, transferId, transferId);
    }

    private LedgerEntryResponse entry(ResultSet row, int rowNumber) throws SQLException {
        long amount = row.getLong("amount_minor");
        return new LedgerEntryResponse(row.getString("id"), row.getObject("transfer_id", UUID.class),
                row.getObject("account_id", UUID.class), row.getObject("counterparty_id", UUID.class),
                row.getString("counterparty_name"), row.getString("currency"), amount < 0 ? "DEBIT" : "CREDIT",
                Long.toString(amount), row.getBigDecimal("running_balance_minor").toPlainString(),
                row.getTimestamp("created_at").toInstant());
    }
}
