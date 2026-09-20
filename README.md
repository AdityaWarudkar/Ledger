# Ledger

A payments ledger service with an operations dashboard, built in eight phases.
Phases 1 and 2 implement accounts, atomic transfers, an immutable double-entry
ledger, and reconciliation. Idempotency, webhooks, and the Next.js dashboard
are subsequent phases.

## Run locally

Install Docker Desktop and start its Linux engine, then run from the repository root:

```sh
docker compose up --build -d
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/accounts
```

The first build downloads Java and Maven images. The API listens on port 8080;
PostgreSQL is exposed on port 15432 to avoid clashing with local installations.
Both ports are bound to localhost. The local profile creates four merchant
accounts and one clearing account. Northstar Commerce starts with INR 25,000.00,
backed by a funding journal that debits the clearing account by the same amount.
Other merchants start at zero. There is no dashboard yet.

```sh
curl -i http://localhost:8080/api/accounts \
  -H 'Content-Type: application/json' \
  -d '{"name":"Harbour Coffee","currency":"INR"}'
```

PowerShell equivalent:

```powershell
Invoke-RestMethod http://localhost:8080/api/accounts -Method Post `
  -ContentType 'application/json' `
  -Body '{"name":"Harbour Coffee","currency":"INR"}'
```

Stop services with `docker compose down`. The database volume is retained.
The credentials in Compose are for local development. Authentication and
authorization are not implemented; this service is not ready for public exposure.

## Develop and test

Use JDK 21 and the included Maven wrapper. For a backend process outside Docker:

```sh
docker compose up -d postgres
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

On Windows, use `.\mvnw.cmd` instead of `./mvnw`.
If the Compose backend is already running, stop it before starting a local backend.

```sh
cd backend
./mvnw verify
```

Tests use Testcontainers and require a running Docker engine. They run against
an isolated PostgreSQL database, apply the real Flyway migration, and exercise
HTTP validation, persistence, pagination, missing accounts, exact money
serialization, and the database overdraft constraint. Tests fail when Docker is
unavailable rather than silently skipping database verification.

The transfer tests cover exact debit/credit posting, insufficient funds, inactive
accounts, currency mismatch, amount precision and overflow, database failure
rollback, immutable history, deferred journal constraints, paginated running
balances, and reconciliation drift. The larger concurrency suite belongs to
Phase 4; load-test results belong to Phase 8.

Phase 2 verification: Java 21 `mvnw verify` passed 24 integration tests with no
failures or skips. The existing Phase 1 database upgraded through Flyway to V2.
A live INR 125.00 demo transfer produced exactly two opposite entries, and
reconciliation returned `balanced: true` with no mismatched accounts.

Configuration: `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` override the local
database defaults. The `local` Spring profile adds sample accounts through a
separate Flyway location. Use a separate database for each environment; do not
switch a seeded database to a profile that omits its migration history.

## Account API

| Method | Path | Result |
| --- | --- | --- |
| POST | `/api/accounts` | Creates an active, zero-balance account; 201 with Location |
| GET | `/api/accounts?page=0&size=25` | Accounts, newest first; maximum page size 100 |
| GET | `/api/accounts/{id}` | Account details |
| GET | `/api/accounts/{id}/balance` | Account ID, currency, and balance in minor units |

Creation accepts `name` (1–120 characters, surrounding whitespace trimmed) and
`currency` (`INR`, `USD`, `EUR`, or `GBP`). All supported currencies currently
have two decimal places. Unknown fields, including client-supplied balances or
statuses, are rejected. Names need not be unique; UUIDs identify accounts.

Amounts use PostgreSQL `BIGINT` and Java `long`. JSON returns `balanceMinor` as
a decimal string so JavaScript cannot round large integers. For example,
`"125050"` means INR 1,250.50 for an INR account. The dashboard will format this
without floating-point arithmetic.

Validation returns 400 using Problem Details and field errors. Missing accounts
return 404. Malformed JSON and invalid UUIDs return 400.
Runnable examples are in `requests/accounts.http` for IntelliJ or the VS Code
REST Client extension.

## Transfers and ledger

| Method | Path | Result |
| --- | --- | --- |
| POST | `/api/transfers` | Posts a transfer; 201 with Location |
| GET | `/api/transfers?page=0&size=25` | Transfers, newest first; optional `accountId` and `kind` filters |
| GET | `/api/transfers/{id}` | Transfer details and its two ledger entries |
| GET | `/api/accounts/{id}/entries?page=0&size=25` | Ledger history, newest posting first, including running balances |
| GET | `/api/system/reconciliation` | Balance mismatches and net ledger amount per currency |

For example, move INR 125.00 from Northstar Commerce to Monsoon Supply Co.:

```powershell
Invoke-RestMethod http://localhost:8080/api/transfers -Method Post `
  -ContentType 'application/json' `
  -Body '{"fromAccountId":"018f0000-0000-7000-8000-000000000001","toAccountId":"018f0000-0000-7000-8000-000000000002","amountMinor":"12500"}'
```

Send amounts as decimal integer strings, in minor units. The backend also
accepts JSON integers, but JavaScript clients should use strings to avoid
rounding before the request is sent. Fractions, zero, negative values, and
amounts exceeding `9223372036854775807` are rejected. Currency comes from the
accounts; cross-currency transfers are rejected. Both accounts must be active
merchants. Public account creation always creates a merchant.

Phase 2 does not implement idempotency. Each successful POST moves money again,
even with an `Idempotency-Key` header. Do not automatically retry a request whose
outcome is unknown. The next phase will make retries safe.

Only completed transfers are stored in this phase. Business rejections return
422 Problem Details with a stable `code` such as `INSUFFICIENT_BALANCE`,
`ACCOUNT_INACTIVE`, `CURRENCY_MISMATCH`, `CLEARING_ACCOUNT`, `SAME_ACCOUNT`, or
`BALANCE_LIMIT`. Missing accounts return 404; invalid request fields return 400.
Rejected requests do not change balances or create journal rows. Failed-event
recording will be added with the outbox phase.

Ledger amounts are signed: a debit reduces an account balance and a credit
increases it. Entry IDs and amounts are strings in JSON. History is ordered by
posting sequence, not wall-clock time. Running balances include the complete
history before pagination, so page two does not restart at zero.

`FUNDING` journals identify local opening credits; normal API transfers use
`TRANSFER`. Clearing accounts are explicitly marked with `kind: CLEARING` in
account responses. Only clearing accounts may have a negative balance, and the
transfer API rejects them as either sender or recipient. This represents the
external side of demo funding; it is not a production deposit API. The local
funding script uses a fixed journal ID and does not fund again on restart.

Reconciliation reads a consistent database snapshot. It reports `balanced: true`
only when every cached account balance matches its entry sum and each currency's
entries net to zero. It reports discrepancies without repairing or hiding them.
Aggregate arithmetic uses PostgreSQL `NUMERIC`, so totals across many accounts
cannot overflow a Java `long`. Accounts with no entries reconcile to zero.

## Design choices

Requests pass through a controller, a transactional service, and a JPA repository.
Flyway owns the schema; Hibernate validates it on startup instead of changing it.
Database checks enforce supported currencies, statuses, and nonnegative merchant
balances. There is no public funding or balance mutation endpoint.

A transfer locks both account rows in Java UUID order, checks the current
balances, updates them, and inserts its journal in one database transaction.
Consistent ordering prevents opposite-direction transfers from locking the same
pair in opposite orders. Row locks keep the balance check valid until commit.
Optimistic locking would require retrying contended writes; explicit row locks
are easier to reason about for this short transaction. No network calls occur
inside it. All future money writers must use the same ordering. See PostgreSQL's
[locking guidance](https://www.postgresql.org/docs/17/explicit-locking.html).

JPA manages accounts and transfers. A small JDBC repository writes and queries
ledger entries using the same Spring-managed transaction. PostgreSQL rejects
updates, deletes, and truncation of posted history. Composite foreign keys keep
account, transfer, and entry currencies consistent. Deferred constraint triggers
require exactly two entries with the transfer's specified accounts and amounts
at commit; a missing credit cannot commit even if application code forgets it.
The checks are deferred because the transfer row is inserted before its entries.
See [constraint triggers](https://www.postgresql.org/docs/17/sql-createtrigger.html).
Corrections must be new compensating transfers, not edits to existing entries.

The runtime currently shares the local database owner's credentials. A database
owner can disable triggers; separate migration/runtime roles belong in production
hardening. Cached balances are maintained by the service, and reconciliation
detects direct SQL drift. The migration does not invent ledger history for any
balances manually changed before Phase 2.

Offset pagination keeps the initial API simple, with an ID tiebreaker for stable
ordering. Cursor pagination is a reasonable later change for larger datasets.
The schema grows with each phase rather than introducing unused tables now.

Spring Boot 3.5 is used with Java 21 as requested. See the
[Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
and [Flyway PostgreSQL support](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database).

## Next phases

1. Accounts and project setup (implemented).
2. Double-entry ledger and transfers (implemented).
3. Idempotency keys and replay behavior.
4. Concurrency safety and invariant tests.
5. Transactional outbox, webhook worker, and test receiver.
6. Approved design tokens and wireframes, frontend foundation, account screens.
7. Transfer demo, transfers, and webhook screens.
8. Metrics, k6, OpenAPI, full Compose setup, and measured results.

The dashboard direction is a light, table-focused workspace: neutral surfaces,
one restrained accent, thin borders, and monospace IDs and amounts. Design tokens
and screen wireframes will be reviewed before frontend implementation.
