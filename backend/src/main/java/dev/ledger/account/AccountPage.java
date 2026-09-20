package dev.ledger.account;

import java.util.List;

public record AccountPage(List<AccountResponse> items, int page, int size, long totalElements, int totalPages) {}
