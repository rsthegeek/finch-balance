# Finchbalance implementation plan

## Purpose and current state

Implement the concurrent balance-service challenge with a small, readable design. Correctness, idempotency, atomic transfers, useful tests, and explainable trade-offs take priority over extra features.

The project began as a Spring Boot scaffold. Implementation and verified evidence are tracked task by task in TASKS.md; this plan describes the accepted target behavior. Use [TASKS.md](TASKS.md) for execution order and progress, and [AGENTS.md](AGENTS.md) for instructions applying to every Codex session.

## Selected stack and scope

| Setting | Decision |
| --- | --- |
| Coordinates | `me.fekri:finchbalance` |
| Base package | `me.fekri.finchbalance` |
| Runtime | Java 25, explicitly selected by the project owner |
| Framework | Spring Boot 4.0.9 |
| Build | Maven Wrapper; executable JAR |
| Configuration | `.properties` files |
| Persistence | Spring Data JPA / Hibernate |
| Database | Embedded, file-backed H2 for normal execution |
| Schema and seeding | Flyway versioned SQL migrations |
| Coordination | Java `ReentrantLock` keyed by transaction ID and account ID |
| Database transactions | `TransactionTemplate` using the JPA transaction manager |
| Style | Google Java Style for production and test Java |

Java 25 is the owner's explicit change from the original challenge's Java 21 requirement; document it in the submission README. Preserve Java 25 unless the owner requests a change.

Support concurrent callers within one JVM using shared singleton lock registries. Multiple processes, independent application contexts writing concurrently, and direct database writers are outside this guarantee. Neither a SQL transaction nor the composite entry key substitutes for the shared Java transaction-ID lock.

Keep the initial product limited to the Java service interface. REST endpoints, Redis, Kafka, Docker, authentication flows, optimistic locking, automatic retries, cleanup schedulers, and benchmarking infrastructure are outside the required implementation.

## Public API and behavioral contract

```java
public interface BalanceService {
  void credit(String accountId, long amount, String transactionId);

  void debit(String accountId, long amount, String transactionId);

  void transfer(
      String sourceAccountId,
      String destinationAccountId,
      long amount,
      String transactionId);

  long getBalance(String accountId);
}
```

- Model one currency using integer minor units and Java `long` values. Balances stay in `[0, Long.MAX_VALUE]`.
- Validate identifier shape before locking: non-null, nonblank, at most 255 characters. Do not silently trim or change case. Malformed identifiers are rejected without persisted outcomes; they cannot fit the agreed identifier contract.
- For syntactically valid identifiers, the original transaction ID identifies one logical operation across credit, debit, and transfer. The first committed outcome wins. Do not compare duplicate payloads.
- Find existing outcomes before evaluating business rules. A successful duplicate returns normally; a failed duplicate reports the stored failure code. Neither changes balances nor adds entries.
- Validation order for new operations: positive amount; different source/destination for transfer; account existence, source before destination; sufficient funds; exact resulting-balance arithmetic.
- Persist expected business rejections with stable failure codes: `INVALID_AMOUNT`, `SAME_ACCOUNT_TRANSFER`, `ACCOUNT_NOT_FOUND`, `INSUFFICIENT_FUNDS`, and `BALANCE_OVERFLOW`.
- A failed debit remains failed after later funding. A new business attempt needs a new transaction ID. Do not automatically retry business failures.
- Unexpected persistence errors roll back the whole operation. Do not manufacture a final business failure from an infrastructure error. A lost commit acknowledgement may leave the outcome unknown to the caller; later calls consult stored history.
- Debit of the entire balance succeeds and leaves zero. Credit/destination overflow fails before any balance mutation.
- `getBalance` rejects unknown accounts and takes the corresponding account lock. Two separate calls are not a simultaneous multi-account snapshot.
- Account creation, deletion, currency conversion, and ownership changes are outside the service API.

## Data model

### Accounts

| Column | SQL type | Rules |
| --- | --- | --- |
| `id` | `VARCHAR(255)` | Primary key |
| `user_id` | `VARCHAR(255)` | Not null, unique: one account per user |
| `balance` | `BIGINT` | Not null, `CHECK (balance >= 0)` |

`user_id` records ownership; a users table and authentication subsystem are unnecessary for this exercise. Preserve case-sensitive identifiers consistently in Java and H2.

### Transactions

| Column | SQL type | Rules |
| --- | --- | --- |
| `transaction_id` | `VARCHAR(255)` | Original caller-supplied ID |
| `entry_role` | `VARCHAR(16)` | `SOURCE`, `DESTINATION`, `SINGLE` |
| `account_id` | `VARCHAR(255)` | Affected/submitted account ID |
| `counterparty_account_id` | `VARCHAR(255)` | Other transfer account; otherwise null |
| `amount` | `BIGINT` | Positive on success; attempted amount on failure |
| `type` | `VARCHAR(16)` | `CREDIT`, `DEBIT`, `TRANSFER` |
| `status` | `VARCHAR(16)` | `SUCCEEDED`, `FAILED` |
| `failure_code` | `VARCHAR(64)` | Stable code on failure; otherwise null |
| `created_at` | `TIMESTAMP WITH TIME ZONE` | Final entry creation instant |

Use `PRIMARY KEY (transaction_id, entry_role)`. All columns except counterparty and failure code are non-null. Map enum values as strings, not ordinals. Keep account IDs as scalar entity fields, without JPA account associations or account foreign keys: failed operations must retain nonexistent submitted account IDs.

| Outcome | Entries |
| --- | --- |
| Credit/debit success | One successful `SINGLE` |
| Credit/debit failure | One failed `SINGLE` |
| Transfer success | Successful `SOURCE` and `DESTINATION` with equal positive amounts |
| Transfer failure | One failed `SOURCE`, preserving intended destination as counterparty |

Add database checks for allowed enum values; role/type compatibility; transfer/non-transfer counterparty shape; success/failure-code consistency; positive successful amounts; and no successful self-transfer. Allow invalid attempted amounts and self-transfer identifiers in failed rows. `DESTINATION` rows are successful only. Do not expect row checks to enforce completeness or agreement across two transfer rows; the service and tests enforce those invariants.

The composite key supports original-ID lookups but does not prevent different role groups under the same ID. The Java original-ID lock provides that exclusion within the selected deployment scope. Treat a malformed committed group as an integrity error, never as permission to run the request again.

Retain history indefinitely, satisfying at least three days of durable idempotency and allowing complete reconstruction. Do not expire successful entries without a separately designed checkpoint/history strategy.

## Flyway and persistence configuration

- `V1__create_accounts_and_transactions.sql`: tables, constraints, and justified indexes.
- `V2__seed_accounts_and_opening_entries.sql`: accounts A/B/C owned by `user-a`/`user-b`/`user-c`, with balances 100000/50000/0 minor units. Add successful opening-credit entries `seed:opening:A` and `seed:opening:B`. Do not create a zero-amount opening entry for C.
- Seed balances must exactly match their opening history. Reserve the seed IDs as demo data. Migrations must not reset balances on later startup.
- Flyway alone creates/seeds schema. Use Hibernate `ddl-auto=validate`; do not add `schema.sql`, `data.sql`, Hibernate create/update, or a startup seeding runner.
- Use `classpath:db/migration` for versioned migrations. Never edit an applied migration; add a new one.
- Add `(account_id, status, created_at)` only when its history/reconciliation access pattern is implemented. The existing primary key handles transaction-ID lookup.

Intended normal-execution properties, to be implemented by the configuration task:

```properties
spring.application.name=finchbalance
spring.main.web-application-type=none
spring.datasource.url=jdbc:h2:file:./data/finchbalance;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0
spring.datasource.username=sa
spring.datasource.password=
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.sql.init.mode=never
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
```

These credentials are for the embedded local exercise. Ignore local database files in Git. H2's `WRITE_DELAY=0` trades throughput for removing its default commit-to-log-flush delay; do not claim protection against every storage failure. Isolate ordinary integration tests with named in-memory databases and use temporary file-backed databases for restart tests. All integration tests use the real Flyway migrations.

## Locking, transactions, and JPA

```text
Validate identifier shape; reject an existing ambient database transaction
Acquire original transaction-ID lock
    Acquire distinct account locks in sorted account-ID order
        Begin database transaction (READ_COMMITTED)
            Find entries by original transaction ID
            Existing group: replay its recorded outcome
            New operation: validate all expected business conditions
                Rejection: persist one final FAILED entry
                Success: change balance(s), persist final SUCCEEDED entry/entries
        Complete flush and commit, or roll back
    Release account locks in reverse order
Release original transaction-ID lock
Translate a committed business failure to the public exception
```

1. Use separate shared singleton registries for transaction and account locks. Sort account IDs with one consistent comparator and deduplicate self-transfer IDs.
2. Acquire Java locks before opening the database transaction so queued callers do not consume pooled connections.
3. Use `TransactionTemplate` with the JPA transaction manager. Reject ambient transactions so a caller cannot postpone commit beyond Java-lock release. Do not put `@Transactional` on the outer coordinator; do not split account/history writes using `REQUIRES_NEW`.
4. Hold Java locks through the actual commit/rollback, not just callback execution. JPA may defer SQL until flush/commit, and a successful `save`/`persist` call is not proof of commit.
5. Load managed account entities inside the transaction and apply dirty-tracked balance changes. Use no `@Version`, optimistic retry loop, or extra database account-locking protocol.
6. Calculate all final balances and validate expected failures before any mutation. Use exact arithmetic. Sample one `Instant` from an injected UTC `Clock` for both successful transfer entries.
7. Persist entries as append-only history. Caller-assigned IDs must not cause a repository `save`/merge path to overwrite existing history. Prefer an explicit append operation using `EntityManager.persist`, with real database tests.
8. Return an internal outcome from the callback and report business failure only after it commits. Unexpected errors must escape, triggering rollback. Do not catch arbitrary persistence failures and attempt to commit a failure in the same rollback-only transaction.
9. No provisional entry/status is needed: Java's original-ID lock owns in-process execution. Only final outcomes are stored. Validate complete stored groups independently of the incoming retry payload.
10. `getBalance` acquires only its account lock and reads committed database state. Reconciliation holds that account lock across history/current-balance reads or runs after all workers finish. Never acquire a transaction-ID lock while holding only an account lock.

### Keyed-lock lifecycle

Return an owning-thread, exactly-once `AutoCloseable` lease from a small keyed `ReentrantLock` registry. Keep reference counts covering owners and waiting acquirers. Atomically increment before waiting; acquire outside `ConcurrentHashMap.compute`; unlock before atomically decrementing/removing a zero-reference slot. Account for exceptional acquisition paths if interruptible acquisition is introduced.

Do not remove a slot merely because its owner unlocked: an existing waiter might use the old lock while a newcomer creates another. Do not retain every transaction ID forever in memory. Avoid lock striping for accounts because unrelated accounts should progress independently. Both registries can use the same tested implementation with separate instances.

## Organization and tests

Use one Maven module with a small service, immutable request/outcome types, JPA entities/repositories, a lock registry, and configuration. Use constructor injection and `jakarta.persistence` imports. Do not introduce a generic framework or layers without a concrete purpose.

Every implementation task in [TASKS.md](TASKS.md) includes meaningful tests written with the change. Verify observable balances, persisted entry counts/contents, failures, and commit/rollback behavior. Mocks alone cannot establish database atomicity or synchronization correctness. Use real H2 for those assertions; test doubles may introduce controlled faults around real collaborators.

Concurrency tests use bounded executors, controlled start gates, collected futures, bounded completion, and reliable cleanup. No arbitrary sleeps or timing-throughput assertions. Do not wrap concurrent tests in an enclosing test transaction: fixtures must be committed and worker transactions must really complete. Inspect persisted state with a fresh persistence context after commit/rollback.

For the current entry model, reconstruct each balance as successful destination/single-credit amounts minus successful source/single-debit amounts, including opening credits. Use a wide decimal SQL accumulator for lifetime turnover and compare exactly to the stored `long` balance. This query is reconciliation, not the normal balance-read path.

## Completion and trade-offs

Delivery requires source, Maven Wrapper, migrations, relevant tests, README, and a clean `./mvnw verify`. Pin a Java-25-compatible Google formatter via Spotless; enforce formatting and the selected Google Checkstyle rules for main/test Java during `verify`, failing on warnings without suppressing new violations.

The README must explain single-process scope, global-ID exclusion by Java locks, sorted account locking, commit boundaries, final failures versus infrastructure errors, replay after restart, seed history, unbounded retained database history, and target-database testing required before changing DBMS. Make no throughput/latency claims without measurements. Keep the owner able to explain every submitted design choice.

## Primary references

- [Google Java Style](https://google.github.io/styleguide/javaguide.html)
- [Spring Boot 4.0 requirements](https://docs.spring.io/spring-boot/4.0/system-requirements.html)
- [Spring programmatic transactions](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)
- [Spring rollback behavior](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html)
- [Spring Boot database initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html) (use settings supported by pinned Boot 4.0.9)
- [Java 25 ReentrantLock](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/locks/ReentrantLock.html)
- [H2 write-delay setting](https://h2database.com/html/commands.html#set_write_delay)
