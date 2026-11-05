# Finchbalance

A Java balance service for concurrent callers in one JVM. Credit, debit, and transfer commit account changes and final transaction history together. The original transaction ID identifies the first committed outcome across all operation types, including business failures.

The project uses Java **21**, Spring Boot **4.0.9**, Maven Wrapper, Spring Data JPA/Hibernate, Flyway, and embedded file-backed H2. Coordinates are `me.fekri:finchbalance`; the base package is `me.fekri.finchbalance`.

## Build and run

Install a JDK 21 and ensure `java` and `JAVA_HOME` select it. From the repository root:

```sh
./mvnw clean verify
```

This runs all 51 tests, packages the executable JAR, and checks production and test sources with Spotless and Google Checkstyle. Checkstyle warnings fail verification. Formatting uses pinned Spotless 3.10.3 / Google Java Format 1.30.0; checking uses Maven Checkstyle 3.6.0 / Checkstyle 10.26.1.

After editing Java sources:

```sh
./mvnw spotless:apply
./mvnw verify
```

Start the application with:

```sh
java -jar target/finchbalance-0.0.1-SNAPSHOT.jar
```

This is a Java service library inside a non-web Spring Boot application. Starting the JAR initializes and validates the database; it does not start a server or process money requests by itself. Use the Spring `BalanceService` bean from Java code. There are no REST endpoints, account-creation API, or authentication flows.

Normal execution uses `jdbc:h2:file:./data/finchbalance;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0`, relative to the working directory, with the embedded exercise credentials `sa` and an empty password. The `data/` directory and H2 database files are ignored by Git. Startup migrations preserve existing data. `WRITE_DELAY=0` removes H2's default delayed log write; it does not guarantee survival of every filesystem or hardware failure.

Tests use distinct named in-memory databases. File/restart tests and the normal-configuration child-JVM test use temporary directories, so verification does not open the owner's local database.

## Project structure

Production packages live under `src/main/java/me/fekri/finchbalance`:

| Package | Responsibility |
| --- | --- |
| `service` | Balance API, implementation, reconciliation, and internal business/transaction coordination |
| `dto` | Immutable operation requests, final outcomes, and reconciliation results |
| `entity` | JPA accounts, transaction entries, and embedded entry identity |
| `repository` | Focused JPA lookups, append-only history persistence, and reconstruction queries |
| `concurrency` | Keyed lock registries, leases, and ordered operation locking |
| `config` | Spring lock-registry and UTC clock configuration |
| `model` | Shared operation, entry-role, entry-status, and failure-code enums |
| `exception` | Business rejection and committed-history integrity exceptions |
| `validation` | Identifier-envelope validation |

`FinchbalanceApplication` stays in the base package so Spring discovers the subpackages and JPA entities automatically. Tests mirror these packages under `src/test/java`, with shared context, transaction, and thread helpers in `support`. Flyway migrations remain in `src/main/resources/db/migration`.

## Java API

All amounts are integer minor units in one currency, represented by `long`. Positive successful amounts and balances in `[0, Long.MAX_VALUE]` are enforced. Arithmetic overflow is rejected before any balance changes. Debit may consume the full balance.

This complete example obtains the bean and uses the seeded accounts. On a fresh database it prints `1250`. Compile it in an application with this project's runtime dependencies on its classpath; the packaged JAR's nested libraries are not a plain `javac` classpath.

```java
import me.fekri.finchbalance.FinchbalanceApplication;
import me.fekri.finchbalance.service.BalanceService;
import org.springframework.boot.builder.SpringApplicationBuilder;

public class BalanceExample {
  public static void main(String[] args) {
    try (var context = new SpringApplicationBuilder(FinchbalanceApplication.class).run(args)) {
      BalanceService balances = context.getBean(BalanceService.class);
      balances.credit("C", 1000, "example:credit");
      balances.debit("C", 250, "example:debit");
      balances.transfer("A", "C", 500, "example:transfer");
      System.out.println(balances.getBalance("C"));
    }
  }
}
```

Repeating these IDs replays their original successful outcomes. Use distinct IDs for new business attempts. `getBalance` takes the account lock and reads committed state; two separate calls do not form a simultaneous multi-account snapshot. Unknown-account reads raise `BalanceException` with `ACCOUNT_NOT_FOUND` and do not write history.

Identifiers must be non-null, nonblank, and at most 255 Java characters. Case and surrounding whitespace are preserved. Malformed identifiers raise `IllegalArgumentException` before locking and are outside persisted idempotency.

## Final outcomes and failures

For a valid envelope, stored history is checked before current business rules. A successful duplicate returns normally. A failed duplicate raises `BalanceException` with the stored `code()`. Neither adds entries nor changes balances. Payloads are not compared, even when a retry changes operation type or account IDs.

New requests validate in this order: positive amount; different transfer accounts; source/account existence, then destination existence; sufficient funds; exact resulting-balance arithmetic. Expected mutation failures commit final `FAILED` history and are reported only after that commit:

| Code | Rejection |
| --- | --- |
| `INVALID_AMOUNT` | Amount is zero or negative |
| `SAME_ACCOUNT_TRANSFER` | Source and destination IDs are equal |
| `ACCOUNT_NOT_FOUND` | A submitted account does not exist |
| `INSUFFICIENT_FUNDS` | Source balance is too small |
| `BALANCE_OVERFLOW` | Credit or destination balance would exceed `Long.MAX_VALUE` |

A failed debit stays failed after later funding. A new attempt needs a new original ID. Unexpected persistence errors propagate and roll back the transaction; they do not become fabricated business rejections. There is no automatic retry loop. If a commit acknowledgement is lost, the caller may not know the outcome; retrying the original ID consults durable history.

Incomplete or inconsistent committed entry groups raise `HistoryIntegrityException`. The service never repairs them by executing the request again.

## Schema and opening history

Flyway is the sole schema/seed initializer; Hibernate uses `ddl-auto=validate` and SQL initialization is disabled. There are two business tables, plus Flyway's migration bookkeeping table:

| Table | Columns and identity |
| --- | --- |
| `accounts` | `id VARCHAR(255)` primary key; unique non-null `user_id VARCHAR(255)`; nonnegative `balance BIGINT` |
| `transactions` | Composite primary key `(transaction_id, entry_role)`; scalar `account_id` and nullable `counterparty_account_id`; `amount BIGINT`; string `type`, `status`, nullable `failure_code`; `created_at TIMESTAMP WITH TIME ZONE` |

Identifiers are case-sensitive. Enums are stored as strings. Failure code and counterparty are dedicated typed fields. History has no account foreign keys or mandatory account associations, allowing failed unknown-account submissions to be recorded. Entries have no update setters and are appended with `EntityManager.persist`, bypassing save/merge. H2's default timestamp precision is microseconds; both successful transfer entries use the same sampled UTC `Instant`.

| Operation outcome | History |
| --- | --- |
| Credit/debit success or failure | One `SINGLE` entry |
| Transfer success | Matching `SOURCE` and `DESTINATION`, positive equal amounts and reciprocal counterparties |
| Transfer failure | One failed `SOURCE`, retaining the intended destination |

Row checks enforce allowed values, compatible roles/types, counterparty shape, success/failure-code consistency, successful positive amounts, and no successful self-transfer. They cannot enforce agreement or completeness across two rows. The decoder validates committed groups, and Java original-ID locking prevents competing role groups within the deployment scope.

[V1](src/main/resources/db/migration/V1__create_accounts_and_transactions.sql) creates the schema. [V2](src/main/resources/db/migration/V2__seed_accounts_and_opening_entries.sql) inserts:

| Account | Owner | Balance | Opening credit ID |
| --- | --- | ---: | --- |
| A | `user-a` | 100000 | `seed:opening:A` |
| B | `user-b` | 50000 | `seed:opening:B` |
| C | `user-c` | 0 | None |

Reserve those seed IDs for demo data. A/B balances have matching successful opening credits; C has no invalid zero-amount entry. Ownership is explicit without a users/auth subsystem. [V3](src/main/resources/db/migration/V3__index_account_history.sql) adds `(account_id, status, created_at)` for account-scoped history; its account/status prefix matches reconciliation filtering. Original-ID lookups use the primary key.

Applied migrations remain immutable. Add a new versioned migration for changes. Changing DBMS requires target-database migration, mapping, constraint, isolation, rollback, commit, and restart testing; the H2 results alone do not establish another database's behavior.

## Locking and the commit boundary

`OperationCoordinator` rejects ambient Spring database transactions before acquiring locks. The sequence is:

1. Validate the identifier envelope.
2. Acquire the original transaction-ID `ReentrantLock`.
3. Acquire distinct account locks in natural `String` order.
4. Open a `READ_COMMITTED` `TransactionTemplate` transaction using the JPA transaction manager.
5. Replay stored history, or validate, calculate, mutate managed accounts, and append final entries.
6. Finish actual flush and database commit/rollback before releasing accounts in reverse order and then the transaction lock.
7. Report any committed business failure outside the callback.

There is no outer `@Transactional` method, `REQUIRES_NEW` split, optimistic `@Version`, global execution lock, or second database account-locking protocol. A persist/flush is not a commit. Holding Java locks around the complete `TransactionTemplate.execute` call keeps a waiting caller out until transaction completion, including commit-time failures.

Two singleton registries separate transaction IDs from account IDs. Keyed slots count owners and waiters before acquisition, acquire outside map computations, unlock before decrement/removal, and disappear only at zero references. Leases require owning-thread, exactly-once release. Waiting callers therefore share the existing slot, while completed historical IDs do not occupy memory forever. Account reads never acquire a transaction-ID lock.

The guarantee requires one JVM with these shared singleton registries. Independent application contexts, other processes, and direct database writers are outside it. The composite entry key alone does not provide global original-ID exclusion across operation types. No throughput or latency result is claimed.

## Reconciliation and retention

The Spring `BalanceReconciler` bean exposes `reconcile(accountId)`. Its immutable `Reconciliation` result includes the stored balance, exact `BigInteger` reconstructed balance, and `matches()`. A discrepancy is reported rather than silently repaired.

It holds the account lock across both reads in one transaction. Successful destination and single-credit amounts add to history; source and single-debit amounts subtract. Failed entries are excluded, opening credits are included, and a `DECIMAL(100,0)` SQL accumulator avoids `long` turnover overflow. Reconciliation is an audit query; ordinary balance reads load the account directly.

History is retained indefinitely, covering at least three days and preserving complete reconstruction and restart replay. There is no expiry scheduler or ID reuse. Database storage and reconciliation work grow with retained history. Expiry would need a separately designed checkpoint/history strategy.

## Verification and review

The 51 tests cover schema constraints, seeds and restart, every JPA entry form, append-only persistence, identifier/amount boundaries, stable failures, exact arithmetic, keyed-lock lifecycle, lock ordering, commit/rollback, concurrent duplicates and cross-operation races, opposite transfers, independent account progress, durable replay after 96 hours, mixed 1000-call workloads, and reconciliation with turnover beyond `Long.MAX_VALUE`.

Concurrency tests use bounded pools/futures and latches, collect caller results, and clean up in `finally`. Database assertions use real H2 migrations and real transactions. Fault tests introduce controlled failures around actual persistence collaborators; fault controls exist only in test wiring. Concurrent/restart/commit tests have no enclosing test transaction.

Implementation and tests were developed with Codex against the accepted [PLAN.md](PLAN.md) and [AGENTS.md](AGENTS.md). [TASKS.md](TASKS.md) records verification evidence task by task. Review the small coordinator, lock registry, calculation record, and stored-outcome decoder alongside their behavioral tests to defend the design. These tests establish the exercised cases in the stated scope; they are not performance measurements or a multi-process proof.
