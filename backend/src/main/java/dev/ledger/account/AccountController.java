package dev.ledger.account;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {
    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Active merchant account created with a zero balance")
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        var account = accounts.create(request);
        return ResponseEntity.created(URI.create("/api/accounts/" + account.id())).body(account);
    }

    @GetMapping
    public AccountPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
                            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int size) {
        return accounts.list(page, size);
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable UUID id) {
        return accounts.get(id);
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse balance(@PathVariable UUID id) {
        var account = accounts.get(id);
        return new BalanceResponse(account.id(), account.currency(), account.balanceMinor());
    }

    public record BalanceResponse(UUID accountId, String currency, String balanceMinor) {}
}
