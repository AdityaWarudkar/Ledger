package dev.ledger.account;

import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccountService {
    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Transactional
    public AccountResponse create(CreateAccountRequest request) {
        return AccountResponse.from(accounts.save(new Account(request.name().strip(), request.currency())));
    }

    public AccountResponse get(UUID id) {
        return AccountResponse.from(accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id)));
    }

    public AccountPage list(int page, int size) {
        var result = accounts.findAll(PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return new AccountPage(result.map(AccountResponse::from).getContent(), page, size,
                result.getTotalElements(), result.getTotalPages());
    }
}
