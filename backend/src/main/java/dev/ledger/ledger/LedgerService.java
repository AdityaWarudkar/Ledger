package dev.ledger.ledger;

import dev.ledger.account.AccountNotFoundException;
import dev.ledger.account.AccountRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public LedgerService(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LedgerPage forAccount(UUID id, int page, int size) {
        if (!accounts.existsById(id)) {
            throw new AccountNotFoundException(id);
        }
        long count = ledger.countForAccount(id);
        return new LedgerPage(ledger.forAccount(id, page, size), page, size, count,
                count / size + (count % size == 0 ? 0 : 1));
    }

    public record LedgerPage(List<LedgerEntryResponse> items, int page, int size, long totalElements, long totalPages) {}
}
