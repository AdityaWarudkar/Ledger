# Ledger

A payments ledger service with an operations dashboard, built in eight phases.
Phase 1 implements accounts, PostgreSQL migrations, and the backend foundation.
Transfers, ledger entries, webhooks, and the Next.js dashboard are subsequent phases.

## Run locally

Install Docker Desktop and start its Linux engine, then run from the repository root:

```sh
docker compose up --build -d
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/accounts
```

The first build downloads Java and Maven images. The API listens on port 8080;
PostgreSQL is exposed on port 15432 to avoid clashing with local installations.
Both ports are bound to localhost. Four local
sample accounts are created, all with zero balances. There is no dashboard yet.

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

Phase 1 verification: Java 21 `mvnw verify` passed all seven integration tests
with no skips. The Compose image built and started, `/actuator/health` returned
`UP`, and `/api/accounts` returned the four locally seeded accounts. Transfer
invariants and load-test results will be added in their respective phases.

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

## Design choices

Requests pass through a controller, a transactional service, and a JPA repository.
Flyway owns the schema; Hibernate validates it on startup instead of changing it.
Database checks enforce supported currencies, statuses, and nonnegative balances.
There is no balance mutation endpoint. Funding and transfers must produce ledger
entries, which arrive in Phase 2.

Offset pagination keeps the initial API simple, with an ID tiebreaker for stable
ordering. Cursor pagination is a reasonable later change for larger datasets.
The schema grows with each phase rather than introducing unused tables now.

Spring Boot 3.5 is used with Java 21 as requested. See the
[Spring Boot requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
and [Flyway PostgreSQL support](https://documentation.red-gate.com/flyway/reference/database-driver-reference/postgresql-database).

## Next phases

1. Accounts and project setup (implemented).
2. Double-entry ledger and transfers.
3. Idempotency keys and replay behavior.
4. Concurrency safety and invariant tests.
5. Transactional outbox, webhook worker, and test receiver.
6. Approved design tokens and wireframes, frontend foundation, account screens.
7. Transfer demo, transfers, and webhook screens.
8. Metrics, k6, OpenAPI, full Compose setup, and measured results.

The dashboard direction is a light, table-focused workspace: neutral surfaces,
one restrained accent, thin borders, and monospace IDs and amounts. Design tokens
and screen wireframes will be reviewed before frontend implementation.
