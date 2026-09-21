package dev.ledger.transfer;

import dev.ledger.account.*;
import dev.ledger.ledger.LedgerRepository;
import dev.ledger.ledger.LedgerEntryResponse;
import dev.ledger.webhook.OutboxService;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TransferService {
    private final AccountRepository accounts;
    private final TransferRepository transfers;
    private final LedgerRepository ledger;
    private final OutboxService outbox;

    public TransferService(AccountRepository accounts, TransferRepository transfers, LedgerRepository ledger, OutboxService outbox) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.ledger = ledger;
        this.outbox = outbox;
    }

    // These business rejections occur before any money write. The caller may persist their response.
    @Transactional(noRollbackFor = {TransferRejectedException.class, AccountNotFoundException.class})
    public TransferResponse create(CreateTransferRequest request) {
        UUID fromId = request.fromAccountId();
        UUID toId = request.toAccountId();
        if (fromId.equals(toId)) {
            throw new TransferRejectedException("SAME_ACCOUNT", "Source and destination accounts must differ");
        }

        // Every money writer must acquire these locks in the same UUID order.
        UUID firstId = fromId.compareTo(toId) < 0 ? fromId : toId;
        UUID secondId = firstId.equals(fromId) ? toId : fromId;
        Account first = lock(firstId);
        Account second = lock(secondId);
        Account from = fromId.equals(firstId) ? first : second;
        Account to = toId.equals(firstId) ? first : second;
        if (from.getKind() != AccountKind.MERCHANT || to.getKind() != AccountKind.MERCHANT) {
            throw new TransferRejectedException("CLEARING_ACCOUNT", "Clearing accounts cannot be used in transfers");
        }
        if (from.getStatus() != AccountStatus.ACTIVE || to.getStatus() != AccountStatus.ACTIVE) {
            throw new TransferRejectedException("ACCOUNT_INACTIVE", "Both accounts must be active");
        }
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new TransferRejectedException("CURRENCY_MISMATCH", "Accounts must use the same currency");
        }
        long amount = request.amountMinor();
        if (amount <= 0) {
            throw new TransferRejectedException("INVALID_AMOUNT", "Amount must be greater than zero");
        }
        if (from.getBalanceMinor() < amount) {
            throw new TransferRejectedException("INSUFFICIENT_BALANCE", "Insufficient balance");
        }
        if (to.getBalanceMinor() > Long.MAX_VALUE - amount) {
            throw new TransferRejectedException("BALANCE_LIMIT", "Destination balance would exceed the supported limit");
        }

        from.debit(amount);
        to.credit(amount);
        var transfer = transfers.saveAndFlush(new Transfer(fromId, toId, from.getCurrency(), amount));
        ledger.post(transfer);
        var response = TransferResponse.from(transfer);
        outbox.completed(response);
        return response;
    }

    private Account lock(UUID id) {
        return accounts.findByIdForUpdate(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    public TransferDetail get(UUID id) {
        var transfer = transfers.findById(id).orElseThrow(() -> new TransferNotFoundException(id));
        return new TransferDetail(TransferResponse.from(transfer), ledger.forTransfer(id));
    }

    public TransferPage list(UUID accountId, TransferKind kind, int page, int size) {
        var result = transfers.search(accountId, kind, PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return new TransferPage(result.map(TransferResponse::from).getContent(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }

    public record TransferDetail(TransferResponse transfer, List<LedgerEntryResponse> entries) {}
    public record TransferPage(List<TransferResponse> items, int page, int size, long totalElements, int totalPages) {}
}
