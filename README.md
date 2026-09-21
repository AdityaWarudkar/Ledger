# Ledger

A payments ledger service with an operations dashboard, built in eight phases.
Phases 1–5 implement accounts, atomic transfers, an immutable double-entry
ledger, reconciliation, idempotency, concurrency tests, and webhook delivery.
The Next.js dashboard is the next phase.

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
balances, and reconciliation drift. The concurrency suite exercises competing
writers and verifies ledger invariants. Load-test results belong to Phase 8.

Phase 2 verification: Java 21 `mvnw verify` passed 24 integration tests with no
failures or skips. The existing Phase 1 database upgraded through Flyway to V2.
A live INR 125.00 demo transfer produced exactly two opposite entries, and
reconciliation returned `balanced: true` with no mismatched accounts.

Phase 3 verification: all 38 integration tests passed with no failures or skips.
The running service upgraded to V3. A demo request replayed the identical 201
response before and after restarting the backend, while a changed payload
returned 422. Its transfer still had exactly two entries and reconciliation
remained balanced.

Phase 4 verification: all 46 integration tests passed with no failures or skips,
including eight concurrency cases. The batch scenarios made 1,140 requests
through the transactional idempotency service using twelve worker threads.
No deadlocks, lost updates, negative merchant balances, or reconciliation
mismatches were observed. The existing runtime locking implementation passed;
this phase adds regression tests and documents its guarantees.

Phase 5 verification: all 59 integration tests passed with no failures or skips.
The Compose database upgraded to V4. The scheduled worker recorded HTTP 500,
then HTTP 200 after receiver recovery, then another HTTP 200 on manual replay.
All three attempts referred to one event; the receiver stored one receipt and
the ledger remained balanced. Tests also cover dead-letter recovery, timeouts,
signature rejection, competing claims, stale lease fencing, and outbox rollback.

## Concurrency checks

Run only the concurrency suite with Docker running:

```sh
cd backend
./mvnw -Dtest=TransferConcurrencyTest test
```

On Windows, use `.\mvnw.cmd`. The tests start an isolated PostgreSQL container;
they do not change the accounts in the local Compose database.

| Scenario | Check |
| --- | --- |
| 60 competing debits with distinct keys | A balance of 1,000 minor units funds exactly ten transfers of 100; fifty are rejected |
| 240 opposite-direction transfers | Both accounts return to their initial balances and every request completes |
| 240 transfers across six accounts, for each of three fixed random seeds | Each final balance matches an independently calculated total |
| 120 requests sharing twenty keys | Exactly twenty transfers; one hundred responses are replays |
| A completed write held inside an uncommitted transaction | Reconciliation and balance readers see the previous committed state until release |
| A second debit blocked behind a full-balance debit | After the first commit, the second request sees the exhausted balance and is rejected |

Each scenario checks merchant totals per currency, nonnegative balances, exactly
two balanced entries per transfer, zero net ledger entries, historical running
balances, and account-by-account reconciliation. Clearing balances participate
in ledger checks but are excluded from the merchant nonnegative-balance rule.

Worker threads start together at a barrier and call the real Spring transactional
services. Controlled blocking tests use latches and PostgreSQL lock observations
instead of guessing with sleeps. Worker results must complete within a deadline;
exceptions are propagated to the test. The normal ten-connection pool also means
the twelve workers exercise connection queuing. Existing MockMvc tests cover
the HTTP contract separately.

The lock order is Java's `UUID.compareTo` order, consistently applied to both
accounts before validating balances. Future money writers must use that exact
order; it should not be mixed with PostgreSQL's UUID sort order. Locks remain
held until the enclosing idempotency transaction commits or rolls back. Each
request runs at READ COMMITTED; reconciliation uses a separate REPEATABLE READ
snapshot so its multiple queries see one committed database state.

These tests establish regression evidence for the exercised workloads. They
are not throughput measurements or proof across every possible schedule.
The k6 phase will measure HTTP throughput and tail latency separately.

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
$transferKey = [guid]::NewGuid().ToString()
Invoke-RestMethod http://localhost:8080/api/transfers -Method Post `
  -Headers @{ 'Idempotency-Key' = $transferKey } `
  -ContentType 'application/json' `
  -Body '{"fromAccountId":"018f0000-0000-7000-8000-000000000001","toAccountId":"018f0000-0000-7000-8000-000000000002","amountMinor":"12500"}'
```

Send amounts as decimal integer strings, in minor units. The backend also
accepts JSON integers, but JavaScript clients should use strings to avoid
rounding before the request is sent. Fractions, zero, negative values, and
amounts exceeding `9223372036854775807` are rejected. Currency comes from the
accounts; cross-currency transfers are rejected. Both accounts must be active
merchants. Public account creation always creates a merchant.

`Idempotency-Key` is required for every transfer. Keep the same key and payload
when retrying, including after a network failure. Generate a new key only for a
new transfer. In the PowerShell example, reuse `$transferKey` when repeating
the request; do not rerun the line that generates it.

Only completed transfers are stored in this phase. Business rejections return
422 Problem Details with a stable `code` such as `INSUFFICIENT_BALANCE`,
`ACCOUNT_INACTIVE`, `CURRENCY_MISMATCH`, `CLEARING_ACCOUNT`, `SAME_ACCOUNT`, or
`BALANCE_LIMIT`. Missing accounts return 404; invalid request fields return 400.
Rejected requests do not change balances or create journal rows. A new business
rejection records a `transfer.failed` outbox event alongside its idempotency
response. Replaying the rejection does not create another event.

## Idempotency

Keys are case-sensitive, 1–128 characters, using letters, digits, `.`, `_`, `:`,
or `-`. UUIDs are a convenient choice. Missing, duplicate, or invalid keys return
400. Keys currently belong to the transfer endpoint globally; this local service
has no authenticated tenant scope. An authenticated deployment must scope keys
to the caller before exposing stored responses.

| Request | Response and ledger effect |
| --- | --- |
| New key, valid transfer | 201; one transfer and two entries; `Idempotency-Replayed: false` |
| Same key and payload | Original status, body, content type, and Location; no new entries; `Idempotency-Replayed: true` |
| Same key, different valid payload | 422 with `IDEMPOTENCY_KEY_REUSED`; original response is preserved |
| Same key while the first transaction is open | Waits up to five seconds; then replays or returns 409 with `IDEMPOTENCY_IN_PROGRESS` and `Retry-After: 1` |
| Business rejection or missing account | Stores the original 422 or 404; later retries replay it even if account state changes |
| Invalid JSON or request fields | 400; no key is reserved |
| Database failure before commit | Key, transfer, entries, and balance updates roll back together; the same key can be retried |

Request fingerprints use SHA-256 over the parsed source UUID, destination UUID,
and integer amount. Field order, JSON whitespace, and integer strings versus
JSON integers do not change the fingerprint. Any change to a transfer field
does. Request validation happens before claiming the key.

The original JSON body is stored as text, rather than reconstructed from the
transfer later. This preserves timestamp precision and the original response
after a restart or an account-state change. Replays retain 201 for an originally
created transfer. Transport headers such as Date are not persisted.

The key claim, response, and transfer use one PostgreSQL transaction. A unique
key constraint with `INSERT ... ON CONFLICT DO NOTHING` arbitrates concurrent
requests across application instances. After the winning transaction commits,
the next statement reads its saved response at READ COMMITTED isolation. If it
rolls back, a waiting insert can claim the key and perform the transfer. This
avoids a separate cache or a durable processing flag that could get stuck after
a crash. See PostgreSQL's [transaction isolation documentation](https://www.postgresql.org/docs/17/transaction-iso.html).

A deferred database constraint prevents incomplete claims from committing.
Completed responses cannot be edited or deleted. Keys do not expire in this
phase; retaining them avoids unexpectedly treating an old retry as a new
transfer. A production retention policy must define that boundary explicitly.
Business exceptions are raised before money writes and are caught to save the
rejection response. Unexpected exceptions still roll back the whole transaction.

The test suite includes eight simultaneous requests with one key, different
payloads racing for a key, a waiter observing commit or rollback, the five-second
timeout, cached rejections, and a database failure while saving the response.

## Ledger history and reconciliation

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

## Webhooks

Register one endpoint per account before making a transfer. Successful transfers
create a `transfer.completed` event and a delivery for each registered source or
destination account. A business rejection creates `transfer.failed` and notifies
the source account's registered endpoint, if one exists. Invalid requests and
idempotency conflicts do not create events. Events are retained even when no
endpoint is registered; registration does not backfill old events. Local opening
funding and pre-Phase-5 transfers are not backfilled either.

| Method | Path | Result |
| --- | --- | --- |
| POST | `/api/webhooks/endpoints` | Register `{accountId, url}`; returns a generated signing secret once |
| GET | `/api/webhooks/endpoints?page=0&size=25` | Registered endpoints, without secrets |
| GET | `/api/webhooks/deliveries` | Delivery list; optional `endpointId`, `status`, `page`, and `size` |
| GET | `/api/webhooks/deliveries/{id}` | Delivery, event payload, and complete attempt history |
| POST | `/api/webhooks/deliveries/{id}/replay` | Queue a delivered or dead-letter delivery again; 202 |

Lists return JSON arrays with at most 100 rows per page. Delivery states are
`PENDING`, `PROCESSING`, `RETRY`, `DELIVERED`, and `DEAD_LETTER`. Manual replay
retains the event ID and all previous attempts while resetting the retry budget.
Replaying an already pending or processing delivery returns 409.

The default worker polls every second and claims up to ten deliveries serially
per poll. Each HTTP request has a two-second timeout. All non-2xx responses and
transport failures are retried up to five total attempts per cycle. Backoff
doubles from a two-second base, is capped at five minutes, and uses random jitter
between half and all of that delay. Exhausted deliveries enter `DEAD_LETTER`.
Attempts record start/end time, status code, latency, error, and the next retry
time. Unknown outcomes, such as a worker crash, have no invented HTTP status or
latency.

Outbox events and initial delivery rows are inserted inside the transfer's
database transaction. The worker claims work with `FOR UPDATE SKIP LOCKED`,
records an attempt, and commits a 30-second lease before making the HTTP call.
It then records the outcome in a separate short transaction. Multiple workers
can claim different rows. An expired lease is recoverable; a unique lease token
prevents an older worker's result from overwriting a newer attempt. Crashed
attempts count toward the retry limit. See PostgreSQL's
[queue-locking behavior](https://www.postgresql.org/docs/17/sql-select.html).

Delivery is at least once, with no ordering guarantee between events. A receiver
may accept an event even if the sender times out or crashes before recording
success. Consumers must deduplicate event IDs and commit the deduplication record
with their business side effects. A successful transfer never waits for HTTP.

### Signatures

Each POST carries `X-Ledger-Event-Id`, `X-Ledger-Endpoint-Id`,
`X-Ledger-Timestamp`, and `X-Ledger-Signature`. The body is an envelope with
`id`, `type`, `createdAt`, and `data`. Amounts in `data` remain integer strings.

The timestamp is Unix seconds. Compute HMAC-SHA256 over the UTF-8 bytes of
`timestamp + "." + rawBody`, using the signing secret's literal UTF-8 bytes
(do not hex-decode it). The signature is `v1=` followed by lowercase hex. Verify
the signature with a constant-time comparison, require a timestamp within five
minutes, and verify that the event header matches the signed body's ID. Each
retry is signed with its current timestamp, while the event body stays unchanged.

Secrets are random 256-bit values represented as hex and returned only by the
registration response. This local project stores them in PostgreSQL because the
sender must retrieve them to sign requests. Production hardening should encrypt
them using managed keys and restrict access to the database.

### Local receiver demo

The `local` profile enables a built-in receiver. Register this URL when using
the default Compose ports:

```text
http://localhost:8080/api/webhooks/test-receiver
```

Here `localhost` is resolved by the backend container. If the service runs on a
different internal port, use that port. The receiver configuration is shared
across local subscriptions and persists in PostgreSQL.

| Method | Path | Behavior |
| --- | --- | --- |
| GET / PUT | `/api/webhooks/test-receiver/behavior` | Read/set `{ "behavior": "HEALTHY" }`, `FAIL`, or `SLOW` |
| POST | `/api/webhooks/test-receiver` | Verify signatures and record each endpoint/event pair once |
| GET | `/api/webhooks/test-receiver/receipts` | Inspect deduplicated receipts; optional `endpointId`, `page`, and `size` |

`FAIL` returns HTTP 500 without recording a receipt. `SLOW` waits three seconds
before accepting, which intentionally exceeds the sender's timeout. `HEALTHY`
accepts promptly and acknowledges duplicates without applying a second effect.
The receipt insert is the demo's business side effect. The receiver routes are
absent outside the local profile.

Use `requests/webhooks.http` to register an endpoint, switch to failure mode,
send a transfer with a new idempotency key, and inspect retries. Switch back to
`HEALTHY` to recover. If the retry budget has already run out, replay the
dead-letter delivery. Replaying a delivered event leaves the receipt count
unchanged. Finish the demo in `HEALTHY` mode.

### Destination configuration

The worker follows no HTTP redirects and validates the configured host before
each attempt. Outside the local profile, HTTPS is required and the allowed-host
list is empty until `WEBHOOK_ALLOWED_HOSTS` is set to a comma-separated list of
trusted receiver hosts. The local profile permits HTTP only to the configured
`localhost`, `127.0.0.1`, and `backend` hosts. These are explicit operator-trusted
destinations, not arbitrary user-supplied URLs. Apply network egress restrictions
as well when deploying; a host allowlist is not DNS pinning.

`WEBHOOK_WORKER_ENABLED=false` disables scheduling. Request timeout, lease
duration, retry limits, and polling are configured under `ledger.webhooks` in
`application.yaml`; the lease must exceed the HTTP timeout by more than a second.
Tests disable scheduling and invoke the worker deterministically against a real
HTTP receiver and PostgreSQL.

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
3. Idempotency keys and replay behavior (implemented).
4. Concurrency safety and invariant tests (implemented).
5. Transactional outbox, webhook worker, and test receiver (implemented).
6. Approved design tokens and wireframes, frontend foundation, account screens.
7. Transfer demo, transfers, and webhook screens.
8. Metrics, k6, OpenAPI, full Compose setup, and measured results.

The dashboard direction is a light, table-focused workspace: neutral surfaces,
one restrained accent, thin borders, and monospace IDs and amounts. Design tokens
and screen wireframes will be reviewed before frontend implementation.
