# RetailBank360

A retail banking POC in Java 17 and Spring Boot 3.5: customer accounts, deposits, withdrawals,
internal transfers and consumer loans, built as microservices with **database locking for multi-user
operations** as the headline feature.

Everything runs with zero setup on an embedded H2 database. A PostgreSQL profile is included for a
realistic deployment.

---

## Status: complete

All ten modules are built, wired and tested. **193 main classes, ~15,200 lines, 97 REST endpoints,
24 test classes, 153 tests, 12 database migrations** - `mvn clean install` is green on JDK 17, and the
whole platform has been run end to end and verified over HTTP.

| # | Module | Port | Status | What it contains |
| --- | --- | --- | --- | --- |
| 1 | `retailbank-common` | library | **Complete** | 70 classes. Distributed lock framework, idempotency store, JWT + MFA token service, AES-GCM crypto with blind indexes, rate limiter, retry template, shared error contract, audit and notification publishers - all delivered as Spring Boot auto-configurations |
| 2 | `retailbank-discovery-server` | 8761 | **Complete** | Eureka registry. Optional: services default to configured URLs |
| 3 | `retailbank-api-gateway` | 8080 | **Complete** | Reactive Spring Cloud Gateway, 8 routes, edge JWT verification, CORS, correlation ids |
| 4 | `retailbank-auth-service` | 8081 | **Complete** | 21 classes, 14 endpoints, 2 entities. Login, BCrypt, RFC 6238 TOTP MFA, refresh-token rotation and revocation, lockout, role assignment |
| 5 | `retailbank-customer-service` | 8082 | **Complete** | 15 classes, 13 endpoints. Customer master data, KYC lifecycle, encrypted PII with blind-index lookups, internal profile and contact projections |
| 6 | `retailbank-account-service` | 8083 | **Complete** | 28 classes, 19 endpoints, 3 entities. **Balances and the immutable ledger.** Atomic transfers, three-layer locking, statements with CSV export, change history |
| 7 | `retailbank-transaction-service` | 8084 | **Complete** | 15 classes, 14 endpoints. Deposit / withdrawal / transfer as orchestrated sagas, reconciliation of unknown outcomes, compensating reversal |
| 8 | `retailbank-loan-service` | 8085 | **Complete** | 25 classes, 21 endpoints, 2 entities. Eligibility scoring, amortisation, disbursement saga, repayment, automatic EMI collection, 90-day write-off |
| 9 | `retailbank-notification-service` | 8086 | **Complete** | 7 classes, 5 endpoints. Event intake, resolves the recipient itself, masks before logging |
| 10 | `retailbank-audit-service` | 8087 | **Complete** | 8 classes, 6 endpoints. Hash-chained immutable trail, sharded per source service, chain verification, CSV export |

## What was developed, and how

The starting point was a skeleton: one partly working service backed by an in-memory `ArrayList`, a
repository that returned `null`, an entity with no `@Id`, six "Hello World" classes, and a database
that was never connected. Sections 2 and 3 below set that out in full.

Everything below was then built on top of it, keeping the existing module layout and the
`controller -> service -> repository -> entity` style.

**The banking domain.** Customers with KYC, accounts with product rules, an append-only ledger,
deposits, withdrawals, transfers, statements, consumer loans with amortisation schedules, repayments
and write-off.

**The locking feature**, which the requirement asks for by name. Three cooperating layers -
optimistic `@Version`, `SELECT ... FOR UPDATE` in ascending id order, and a database-backed
distributed lock with TTL, fencing tokens, orphan recovery, re-entrancy and a full audit trail.
Documented in detail further down.

**Correctness under concurrency.** Idempotency keys on every money-moving API, bounded retry with
jitter for lost races, deadlock *prevention* through lock ordering, and reconciliation for outcomes
that were never observed.

**Distributed transactions.** A transfer is one local ACID commit because both balances live in one
service; the workflow around it is an orchestrated saga with explicit compensation.

**Security.** JWT with four roles plus an internal service principal, optional TOTP MFA, row-level
ownership checks, PII encrypted at rest with searchable blind indexes, masking on the way out, rate
limiting, and secrets that fail closed rather than shipping a default.

**Operations.** Flyway migrations generated from the JPA mappings, `validate` mode, service
discovery, executable jars, Docker Compose, scheduled housekeeping, and a tamper-evident audit trail.

**How it was verified.** 153 automated tests, including the concurrency, orphaned-lock and
multi-instance acceptance tests the requirement names. Then the whole platform was started - nine
processes - and driven over HTTP through the gateway: KYC gate, transfer, idempotent replay, 40
concurrent transfers landing on an exact balance, loan disbursement, automatic EMI collection, the
90-day write-off, notification delivery, audit-chain verification across five shards, lock
inspection, and authorization.

Running it that way is what caught the bugs the test suite could not: a shared auto-configuration
that broke services without Feign, a test config silently shadowing the gateway's routes, and a
misplaced YAML key that quietly downgraded every service to an in-memory database.

---

## 1. Current architecture

Ten Maven modules under one reactor, all in the `org.retailbank360` package, each following the same
`controller -> service (+Impl) -> repository -> entity` layering.

| Module | Port | Owns |
| --- | --- | --- |
| `retailbank-common` | - | Shared library: security, locking, idempotency, crypto, error contract |
| `retailbank-discovery-server` | 8761 | Eureka service registry (optional) |
| `retailbank-api-gateway` | 8080 | Single entry point, reactive Spring Cloud Gateway |
| `retailbank-auth-service` | 8081 | Logins, JWT issuance, TOTP MFA, refresh tokens |
| `retailbank-customer-service` | 8082 | Customer master data, KYC, credit score |
| `retailbank-account-service` | 8083 | **Balances and the immutable ledger** |
| `retailbank-transaction-service` | 8084 | Deposit / withdrawal / transfer orchestration |
| `retailbank-loan-service` | 8085 | Loan origination, disbursement, repayment |
| `retailbank-notification-service` | 8086 | Customer notifications |
| `retailbank-audit-service` | 8087 | Tamper-evident audit trail |

> Account-service previously shared port 8084 with transaction-service, so neither could run
> alongside the other. It now owns 8083.

### The central design decision

**Money lives in exactly one service.** Both sides of a transfer are rows in account-service's own
database, so a debit and its matching credit commit inside a single local `@Transactional` unit:
either both land or neither does. That makes a transfer genuinely ACID rather than a best-effort pair
of remote calls, and it means there is no half-transfer to compensate for.

The orchestration *around* money movement is where the distributed-transaction patterns live:
transaction-service and loan-service are **orchestrated sagas** that record intent, invoke the atomic
ledger step, and then confirm or compensate.

## 2. What already existed

The starting point was a skeleton:

- **customer-service** was the only partly working service: a JPA-annotated `Customer` entity, but an
  in-memory `ArrayList` repository behind it.
- **account-service** had an `Account` entity and a controller, but `AccountRepositoryImpl` returned
  `null` and `List.of()` and carried no `@Repository`, so the context could not start.
- **transaction-service** had enums, four DTOs, a `Transaction` entity **with no `@Id`**, an empty
  controller and an all-`null` service.
- **auth, loan, audit, notification, gateway and common** contained only a "Hello World" `App.java`
  with no `@SpringBootApplication`.
- Every application excluded `DataSourceAutoConfiguration`, so no database was ever connected.

## 3. Gaps that were closed

No authentication, authorization, working persistence, money movement, loans, statements, validation
rules, PII protection, rate limiting, idempotency, locking, ledger or audit trail. Plus the port
collision, an `application.yml` shipped inside the shared library jar, and six modules sharing a
duplicate `org.retailbank360.App` class.

## 4. Approach

The module layout and coding style were kept. Cross-cutting infrastructure went into
`retailbank-common` as **Spring Boot auto-configurations** rather than component-scanned beans, so
each piece states its own preconditions and backs off cleanly - which is what lets the reactive
gateway and the database-less notification service depend on the same shared module as the JPA
services.

Two rules follow from that and are worth knowing before editing the shared module:

- **Nothing in `retailbank-common` is component-scanned.** Every module uses the `org.retailbank360`
  package, so a plain `@Component` there would be picked up even by services that cannot support it.
  Each service's `@SpringBootApplication` therefore scopes `scanBasePackages` to its own packages.
- **A `@Bean` method's return type is loaded when its configuration class is read**, before any
  method-level condition is evaluated. Anything referencing an optional dependency - Feign, JPA, the
  servlet API - lives in its own auto-configuration behind a class-level `@ConditionalOnClass`.

---

## Running it

Requires **JDK 17** and Maven. Nothing else: the default profile uses an embedded database, and
the services address each other by configured URL, so neither a database server nor the discovery
server has to be running. Both are available when you want them - see below.

```bash
mvn clean install
```

Then start each service in its own terminal:

```bash
mvn -pl retailbank-auth-service         spring-boot:run   # 8081
mvn -pl retailbank-customer-service     spring-boot:run   # 8082
mvn -pl retailbank-account-service      spring-boot:run   # 8083
mvn -pl retailbank-transaction-service  spring-boot:run   # 8084
mvn -pl retailbank-loan-service         spring-boot:run   # 8085
mvn -pl retailbank-notification-service spring-boot:run   # 8086
mvn -pl retailbank-audit-service        spring-boot:run   # 8087
mvn -pl retailbank-api-gateway          spring-boot:run   # 8080
```

A minimum useful set is auth + customer + account + transaction.

Each service exposes Swagger UI at `/swagger-ui.html`, health at `/actuator/health`, and the H2
console at `/h2-console`.

### Profiles

The default profile is `h2`, which writes to `./data/retailbank_<service>` and sets
`AUTO_SERVER=TRUE` so several instances of one service can share a database - which is what makes the
distributed-lock behaviour observable on a single machine.

For PostgreSQL, create the `retailbank_*_db` databases and start with:

```bash
mvn -pl retailbank-account-service spring-boot:run -Dspring-boot.run.profiles=postgres
```

### Everything at once, with Docker

```bash
cp .env.example .env          # then put real values in it
docker compose up --build
```

Brings up PostgreSQL, the discovery server, the seven services and the gateway. Only the gateway
(8080) and the Eureka dashboard (8761) are published; the services reach each other over the compose
network by name, on the `postgres,discovery` profiles.

> The compose and Docker files were written but **not executed** - there is no Docker daemon on the
> machine this was built on. Everything else in this README was run and verified.

### Service discovery

Off by default: the services address each other by configured URL, so nothing extra is needed to run
the POC. To switch the platform to lookup by name, start the discovery server and add the `discovery`
profile:

```bash
mvn -pl retailbank-discovery-server spring-boot:run                                    # 8761
mvn -pl retailbank-account-service  spring-boot:run -Dspring-boot.run.profiles=h2,discovery
```

The profile blanks the configured peer URLs, which is what makes Feign fall back to the load balancer
and resolve `retailbank-customer-service` through the registry. Registered instances are listed at
`http://localhost:8761`.

This is what makes running several instances of one service practical - and several instances is
precisely the case the distributed lock exists for.

### Database migrations

The schema is owned by **Flyway**, not by Hibernate. Migrations live in
`src/main/resources/db/migration/{vendor}` per service, with a set for `h2` and one for `postgresql`,
and Hibernate runs in `validate` mode against them: an entity that has outrun its migration stops the
service at startup rather than silently altering a live schema.

The baselines were generated from the JPA mappings themselves rather than hand-written, so the
migrations and the entities agree by construction. The test suite runs against the real migrations,
which is what keeps them honest as the entities change.

### Secrets

The `h2` profile supplies throwaway keys so the POC runs with no setup. **Every other profile must
supply them through the environment, and a service refuses to start without them** rather than
falling back to anything committed to this repository:

```bash
export RETAILBANK_JWT_SECRET=...        # at least 32 characters, identical on every service
export RETAILBANK_DATA_KEY=...          # base64, AES-256 key for PII columns
export RETAILBANK_BLIND_INDEX_KEY=...   # base64, HMAC key for the searchable indexes
```

The JWT secret must match across all services or tokens issued by auth-service will not verify.
Changing `RETAILBANK_DATA_KEY` makes existing encrypted data unreadable, and changing
`RETAILBANK_BLIND_INDEX_KEY` invalidates every stored index and requires a re-index.

### Log levels

Logging is quiet by default. Turn detail on per run, without rebuilding:

```bash
SHOW_SQL=true SQL_LOG_LEVEL=DEBUG APP_LOG_LEVEL=DEBUG mvn -pl retailbank-account-service spring-boot:run
```

### Packaging

Each service builds an executable jar, so it can also be run directly:

```bash
java -jar retailbank-account-service/target/retailbank-account-service-1.0-SNAPSHOT.jar
```

### Demo logins

Seeded by `DemoUserSeeder` on first start of auth-service. Controlled by
`retailbank.seed-demo-data`, which is off under the `postgres` profile.

| Username | Password | Role |
| --- | --- | --- |
| `admin` | `Admin@123` | ADMIN |
| `teller1` | `Teller@123` | TELLER |
| `officer1` | `Officer@123` | LOAN_OFFICER |
| `customer1` | `Customer@123` | CUSTOMER (customer id 1) |
| `customer2` | `Customer@123` | CUSTOMER (customer id 2) |

### A worked example

```bash
# 1. Log in and keep the access token
curl -s -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"teller1","password":"Teller@123"}'

# 2. Create a customer, then verify KYC (an account cannot be opened before this)
curl -s -X POST localhost:8080/api/v1/customers -H "Authorization: Bearer $TOKEN" ...
curl -s -X POST localhost:8080/api/v1/customers/1/kyc/verify -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"remarks":"documents checked"}'

# 3. Open two accounts, then transfer between them
curl -s -X POST localhost:8080/api/v1/transactions/transfer \
  -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: demo-001' \
  -H 'Content-Type: application/json' \
  -d '{"fromAccountId":1,"toAccountId":2,"amount":"250.00","currency":"INR"}'

# 4. Replay the same Idempotency-Key: the original result comes back, no second posting
```

---

## API reference

All paths are reachable through the gateway on `localhost:8080`, or directly on the service port.
Everything except the login family requires `Authorization: Bearer <token>`.

Listing endpoints take `page` and `size` (default 50, capped at 200), so no request can pull a
whole table into memory.

### auth-service

| Method | Path | Role |
| --- | --- | --- |
| POST | `/api/v1/auth/login` | public |
| POST | `/api/v1/auth/mfa/verify` | public |
| POST | `/api/v1/auth/refresh` | public |
| POST | `/api/v1/auth/logout` | public |
| POST | `/api/v1/auth/register` | ADMIN |
| GET | `/api/v1/auth/me` | any |
| POST | `/api/v1/auth/password` | any |
| POST | `/api/v1/auth/mfa/enable`, `/mfa/disable` | any |
| GET | `/api/v1/auth/users` | ADMIN |
| POST | `/api/v1/auth/users/{id}/enable`, `/disable`, `/unlock`, `/revoke-sessions` | ADMIN |

### customer-service

| Method | Path | Role |
| --- | --- | --- |
| POST | `/api/v1/customers` | TELLER, ADMIN |
| GET | `/api/v1/customers/all` | staff |
| GET / PUT | `/api/v1/customers/{id}` | staff, or the customer themselves |
| DELETE | `/api/v1/customers/{id}` | ADMIN (closes, does not delete) |
| GET | `/api/v1/customers/mobile/{n}`, `/email/{e}`, `/name/{n}` | staff |
| GET | `/api/v1/customers/kyc/pending` | TELLER, ADMIN |
| POST | `/api/v1/customers/{id}/kyc/verify`, `/kyc/reject` | TELLER, ADMIN |
| GET | `/api/v1/customers/internal/{id}/profile` | staff, SERVICE |
| GET | `/api/v1/customers/internal/{id}/contact` | SERVICE only (decrypted contact details) |

### account-service

| Method | Path | Role |
| --- | --- | --- |
| POST | `/api/v1/accounts` | TELLER, ADMIN |
| GET | `/api/v1/accounts/all` | staff |
| GET | `/api/v1/accounts/{id}` | staff, or the owner |
| GET | `/api/v1/accounts/number/{accountNumber}` | staff |
| GET | `/api/v1/accounts/customer/{customerId}` | staff, or the owner |
| PUT | `/api/v1/accounts/{id}/limits` | ADMIN |
| PUT | `/api/v1/accounts/{id}/status` | TELLER, ADMIN |
| DELETE | `/api/v1/accounts/{id}` | ADMIN |
| GET | `/api/v1/accounts/{id}/history` | ADMIN |
| GET | `/api/v1/accounts/{id}/statement` | staff, or the owner |
| GET | `/api/v1/accounts/{id}/statement/export` | staff, or the owner (CSV) |
| GET | `/api/v1/accounts/{id}/ledger` | staff, or the owner |

**Internal ledger API** - the only code path that can change a balance. Requires staff or a `SERVICE`
principal, and every write takes an `Idempotency-Key` header.

| Method | Path |
| --- | --- |
| POST | `/api/v1/accounts/internal/transfer` |
| POST | `/api/v1/accounts/internal/credit`, `/debit` |
| POST | `/api/v1/accounts/internal/reverse` |
| GET | `/api/v1/accounts/internal/entries/{reference}` |
| GET | `/api/v1/accounts/internal/accounts/{id}/summary` |

### transaction-service

| Method | Path | Role |
| --- | --- | --- |
| POST | `/api/v1/transactions/deposit` | TELLER, ADMIN |
| POST | `/api/v1/transactions/withdraw` | owner of the account, or staff |
| POST | `/api/v1/transactions/transfer` | owner of the source account, or staff |
| GET | `/api/v1/transactions/{ref}` | owner, or staff |
| GET | `/api/v1/transactions/all` | staff |
| GET | `/api/v1/transactions/account/{accountId}` | owner, or staff |
| POST | `/api/v1/transactions/{ref}/reverse` | ADMIN |
| POST | `/api/v1/transactions/reconcile` | ADMIN |

### loan-service

| Method | Path | Role |
| --- | --- | --- |
| POST | `/api/v1/loans/eligibility` | owner, or staff |
| POST | `/api/v1/loans` | owner, or staff |
| GET | `/api/v1/loans/{id}`, `/ref/{loanRef}`, `/{id}/schedule` | owner, or staff |
| GET | `/api/v1/loans/all` | staff |
| GET | `/api/v1/loans/customer/{customerId}` | owner, or staff |
| POST | `/api/v1/loans/{id}/approve`, `/reject`, `/disburse` | LOAN_OFFICER, ADMIN |
| POST | `/api/v1/loans/{id}/repay` | owner, or staff |
| POST | `/api/v1/loans/recover-disbursements`, `/mark-overdue` | ADMIN |
| POST | `/api/v1/loans/collect-due`, `/mark-defaulted` | ADMIN |

### audit-service and lock administration

| Method | Path | Role |
| --- | --- | --- |
| POST | `/api/v1/notifications/events` | SERVICE, staff |
| POST | `/api/v1/audit/events` | SERVICE, staff |
| GET | `/api/v1/audit/events` | ADMIN |
| GET | `/api/v1/audit/events/operation/{id}`, `/correlation/{id}` | ADMIN |
| GET | `/api/v1/audit/events/export` | ADMIN (CSV) |
| GET | `/api/v1/audit/verify` | ADMIN |
| GET | `/api/v1/locks` | ADMIN |
| GET | `/api/v1/locks/status?resourceType=&resourceId=` | staff, SERVICE |
| GET | `/api/v1/locks/audit` | ADMIN |
| POST | `/api/v1/locks/reap` | ADMIN |
| DELETE | `/api/v1/locks/{type}/{id}` | ADMIN (break-glass) |

---

## Database locking for multi-user operations

The requested feature, and the part worth reading the code for. Three layers cooperate, each solving
a problem the others cannot.

### Layer 1 - optimistic locking

`@Version` on `Account`, `Customer`, `Loan`, `Transaction` and `UserAccount`. An update computed from
a stale read is rejected at flush time rather than silently overwriting a concurrent change. Right
for low-contention edits, where the correct answer is to tell the second editor to reload.

### Layer 2 - pessimistic row locks

`AccountRepository.findByIdForUpdate` and `lockAllByIdOrdered` issue `SELECT ... FOR UPDATE` with a
five-second lock timeout. A balance change is read-modify-write, so it has to be *serialised*, not
merely detected afterwards.

Granularity is one account row - never a table, never the database - so two tellers working on two
different accounts never wait on each other.

**Deadlock prevention** comes from ordering: rows are always locked in ascending id order, so a
transfer from A to B and a simultaneous transfer from B to A acquire the same locks in the same
sequence and no cycle can form.

### Layer 3 - distributed resource lock

`resource_locks` is a table with one row per lockable resource (`ACCOUNT:42`, `LOAN:7`,
`AUDIT_CHAIN:global`). `DatabaseDistributedLockManager` acquires with a **single conditional UPDATE**,
so exactly one of N competing instances can observe one affected row. It works across threads,
processes and machines.

| Property | How |
| --- | --- |
| **Mutual exclusion** | One atomic compare-and-set. No read-then-write window. |
| **TTL and orphan recovery** | Every lock carries an expiry. A crashed owner's lock becomes stealable the moment its TTL lapses, and `LockReaper` sweeps locks nobody is contending for. No operator action, no restart. |
| **Fencing** | Each acquisition increments a per-resource token, stamped onto the account and onto every ledger entry. A process that stalled, lost its lock and then woke up presents an older token and is refused - it cannot corrupt the new holder's work. |
| **Re-entrancy** | A thread already holding a key may take it again (a transfer locks two accounts; a disbursement locks a loan and an account). Only the outermost release frees it. |
| **Bounded waiting** | Callers wait at most their budget, then receive **HTTP 423 LOCKED** with a `Retry-After` header rather than blocking a request thread. |
| **Auditing** | Every acquire, steal, re-entry, renewal, release, timeout and reap is written to `resource_lock_audit` with the user, timestamp, operation id and fencing token. |

### Why a table rather than Redis or ZooKeeper

The database is already the single source of truth every instance talks to, and it already provides
atomic conditional updates. A one-row compare-and-set gives the same mutual exclusion Redlock or an
ephemeral znode would, without deploying, securing and keeping available a second piece of
infrastructure - and without adding a dependency the requirement did not ask for.

The seam is explicit: services only ever talk to `LockTemplate`, which talks to the
`DistributedLockManager` **interface**. A deployment that already runs Redis drops in a Redlock
implementation and changes nothing else.

### How the layers compose

`MoneyMovementService` wraps every balance change in three nested concerns, in this order:

1. **Idempotency outermost.** A replay of a client key returns the stored result *without taking any
   lock*, so a retrying client never queues behind itself.
2. **Resource lock next**, acquired *before* the transaction opens - a caller waiting for a busy
   account must not hold a database connection while it waits. All accounts involved are locked
   together, in sorted key order.
3. **Transaction innermost**, in `MoneyMovementTxService`, where rows are locked with `FOR UPDATE` and
   balances and ledger entries are written together.

Between layers 2 and 3 sits a bounded retry with exponential back-off and jitter
(`ConcurrencyRetryTemplate`). Deadlocks cannot be designed away entirely, only made rare and
recoverable: the lock ordering makes them rare, the retry makes them recoverable, and layer 1 is what
makes retrying *safe*.

Because the lock must be taken outside the transaction, the locking method and the transactional
method are always on **separate beans** (`MoneyMovementService` / `MoneyMovementTxService`,
`LoanServiceImpl` / `LoanTxService`, `CustomerServiceImpl` / `KycTransactionService`). A
self-invocation would bypass the proxy and run the whole critical section with no transaction at all,
silently turning `FOR UPDATE` into an ordinary read.

### Locked critical sections

Debit-credit transfer, deposit, withdrawal, loan disbursement, loan repayment posting, KYC decisions,
account status and limit changes, and appends to the audit hash chain.

### What the UI gets

- **423 LOCKED** with `Retry-After` when a resource is busy, so a client can back off intelligently.
- `GET /api/v1/locks/status?resourceType=ACCOUNT&resourceId=42` to render "this account is being
  updated by another user" and disable the conflicting action *before* the operator submits.
- `GET /api/v1/locks` and `/api/v1/locks/audit` for the administrative view.

## Distributed transactions

A transfer is **not** a distributed transaction - both balances are in one database, so it is one
local ACID commit. What is distributed is the workflow around it, handled as an orchestrated saga:

1. Reserve the idempotency key.
2. Commit intent (`PENDING` transaction, or a loan moved to `DISBURSING`) *before* calling the ledger,
   so a crash mid-flight leaves a recoverable record rather than a silent gap.
3. Call the ledger. The remote call sits deliberately *between* two short transactions, never inside
   one - holding a transaction open across a network call pins a connection and turns a slow
   downstream into connection-pool exhaustion.
4. Record the outcome, or compensate.

An **ambiguous** outcome (timeout, dropped connection) is deliberately left `PENDING` rather than
guessed at: marking it failed could hide a transfer that committed, and marking it successful could
invent one that never happened. A reconciliation job asks the ledger what actually posted and settles
it either way. Compensation, when needed, writes mirror-image `REVERSAL` ledger entries - nothing is
ever deleted.

---

## Scheduled work

Every job is idempotent and safe to run on all instances at once - either the work is already done, or
it is claimed under a lock.

| Job | Service | Cadence | What it does |
| --- | --- | --- | --- |
| Lock reaper | all with a database | 30s | Reclaims locks whose owner died mid-operation |
| Idempotency purge | all with a database | hourly | Trims expired keys |
| Lock audit retention | all with a database | hourly | Trims lock history past its window |
| Transaction reconciliation | transaction | 60s | Settles transfers whose ledger outcome was never observed |
| Disbursement recovery | loan | 60s | Settles loans stuck mid-disbursement, against the ledger |
| Refresh token purge | auth | hourly | Removes expired session rows |
| Mark overdue | loan | 00:05 daily | Flags instalments past their due date |
| **Collect due EMIs** | loan | 00:30 daily | Debits the customer's account for instalments that have fallen due |
| **Mark defaulted** | loan | 00:45 daily | Writes off loans unpaid for more than 90 days |

Automatic collection goes through the same locked, idempotent repayment path a manual payment uses,
with a key derived from the loan and the instalment number - so a sweep that runs twice collects each
instalment once. A customer who cannot cover the instalment is skipped rather than pushed into an
unauthorised overdraft.

## Notifications

transaction-service and loan-service publish an event carrying a **customer id and nothing else**;
notification-service resolves the address itself from customer-service. That keeps contact details out
of the money services entirely, and confines decrypted PII to a single `SERVICE`-only endpoint whose
consumer masks the value before it reaches a log.

Publishing is asynchronous and failure-tolerant: a notification outage cannot slow down or fail a
transfer.

## Business rules

Enforced in `MoneyMovementTxService`, `AccountServiceImpl` and `LoanEligibilityService`:

- A non-overdraft account can never fall below its **minimum balance**; an overdraft account can never
  fall below its agreed limit. A savings account defaults to a floor of 1000, a current account to
  5000 with a 50000 overdraft.
- **Daily transfer limits** per account, counted against the running total for the calendar day.
- **KYC must be VERIFIED** before an account can be opened. If customer-service cannot be reached the
  request is refused rather than waved through - the control fails closed.
- Cross-currency transfers are refused rather than silently converted. Self-transfers are refused.
  Amounts must be positive.
- A frozen account accepts nothing; a dormant one still accepts credits but no debits. An account
  holding a balance cannot be closed.
- **Loan eligibility**: credit score at least 650, total instalments at most 50% of monthly income
  (FOIR, including existing loans), principal at most 5x annual income, age 21 to 65 at maturity, no
  defaulted loan. Every rule is evaluated even after one fails, so an applicant sees all of them at
  once - along with the amount they *could* borrow.

All money is `BigDecimal` at scale 2 with `HALF_UP` rounding. The `double` amounts in the original
DTOs were replaced: binary floating point cannot represent currency exactly.

## Security and data protection

- **JWT** (HS256) issued by auth-service, verified by every service and at the gateway. Only an access
  or service token opens a resource endpoint - a refresh token or a half-finished MFA challenge is
  explicitly refused. Every token carries a unique `jti`.
- **Roles**: CUSTOMER, TELLER, LOAN_OFFICER, ADMIN, plus an internal SERVICE principal for
  service-to-service calls, so internal endpoints are authenticated rather than merely firewalled.
  Role checks are `@PreAuthorize` on the controllers; **row-level** ownership ("your own account") is
  enforced in the services via `SecurityUtils.requireCustomerAccess`.
- **Optional MFA**: RFC 6238 TOTP implemented against the JDK crypto provider, compatible with any
  authenticator app. The shared secret is encrypted at rest and shown exactly once, at enrolment.
- **Login hardening**: identical responses for an unknown user and a wrong password, so the endpoint
  cannot be used to enumerate accounts; lockout after 5 failures; refresh tokens stored hashed,
  rotated on use, and revoked wholesale on password change.
- **PII encrypted at rest** with AES-256-GCM (`EncryptedStringConverter`): email, phone, address, PAN
  and Aadhaar. Because GCM uses a fresh IV per write, the same input never yields the same ciphertext
  - so each searchable field also carries a keyed **blind index** (HMAC-SHA256) that carries the
  unique constraint and serves exact-match lookups without ever decrypting the table.
- **Masking** on the way out: account numbers, PAN, Aadhaar, phone and email. A customer principal
  never receives a full account number; staff do.
- **Rate limiting** on login, MFA verification, refresh and transfers, keyed by username when
  authenticated and by client IP otherwise, answering 429 with `Retry-After`.
- **Correlation ids** propagated across every service and stamped on every audit record.
- **No usable secret is committed.** Signing and encryption keys are empty by default; the local
  `h2` profile supplies throwaway ones, and any other profile fails to start until the environment
  provides real values. Spring Boot's default in-memory user is switched off in every service, so no
  generated password is printed to the log.


## Audit and the immutable ledger

- `ledger_entries` is **append-only**: JPA lifecycle callbacks reject updates and deletes outright. A
  mistaken posting is corrected with a compensating `REVERSAL` entry that references the original, so
  history always reconstructs and always adds up. Every entry records the balance that followed it.
- `account_change_history` covers the non-monetary critical changes - who raised a transfer ceiling,
  who froze an account, and why.
- `audit_events` is **hash-chained**: each row stores the hash of the row before it plus a hash of its
  own content. Editing or removing any historical row breaks the chain, and
  `GET /api/v1/audit/verify` recomputes the whole chain and names the first broken link. Appends run
  under the shared resource lock, because a chain is only meaningful in one well-defined order.

## Testing

```bash
mvn clean install        # 153 tests across 10 modules
```

| Acceptance criterion from the requirement | Test |
| --- | --- |
| Concurrent transfers on one account serialise; final balances consistent | `ConcurrentTransferIT` - 50 parallel transfers land on exactly 5000/5000, ledger reconciled entry by entry |
| Orphaned lock cleanup after a crash | `DistributedLockIT.orphanedLockIsRecoveredAfterTtl`, `.reaperClearsOrphanNobodyIsWaitingFor` |
| Lock works across service instances | `DistributedLockIT.onlyOneInstanceWinsUnderContention` - 12 managers with distinct node ids over one database |
| Deadlock handling | `ConcurrentTransferIT.oppositeDirectionTransfersDoNotDeadlock`, `ConcurrencyRetryTemplateTest` |
| Idempotent transfers | `IdempotencyServiceIT`, `ConcurrentTransferIT.concurrentReplaysOfOneKeyPostOnce` |
| No negative balances, limits, KYC | `MoneyMovementRulesIT`, `AccountLifecycleIT` |
| ACID debit-credit, rollback on failure | `MoneyMovementRulesIT`, `ConcurrentTransferIT` |
| Saga and compensation | `TransactionSagaIT`, `LoanLifecycleIT` |
| Audit trail immutable and tamper-evident | `AuditChainIT` |
| Authentication, MFA, lockout | `AuthServiceIT`, `TotpServiceTest` |
| PII encrypted and masked | `CustomerServiceIT`, `CryptoServiceTest`, `MaskingUtilTest` |
| Automatic EMI collection and write-off | `LoanLifecycleIT` |
| Notifications resolve the recipient themselves | `NotificationServiceIT` |
| Migrations match the entity mappings | the whole suite, via `ddl-auto: validate` |

The concurrency tests are the interesting ones. `ConcurrentTransferIT` fires 50 simultaneous transfers
at one account and then asserts three things: the arithmetic is exactly right, money is conserved
across the pair, and **every individual ledger entry's recorded balance follows from the one before
it**. A lost update would show up as a final balance that is too high, because two threads read the
same starting figure and one overwrote the other.
