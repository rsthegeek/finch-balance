# Finchbalance

A Java service for crediting, debiting, and transferring account balances safely under concurrent calls. Built with Java 21, Spring Boot 4.0.9, Maven, JPA/Hibernate, Flyway, and file-backed H2.

## Build and run

Select JDK 21 with `JAVA_HOME`, then run from the project root:

```sh
./mvnw clean verify
java -jar target/finchbalance-0.0.1-SNAPSHOT.jar
```

The build runs 51 tests and checks Google Java Style with Spotless and Checkstyle. Format Java with `./mvnw spotless:apply`.

The JAR initializes the database; it does not start an HTTP server. Use the Spring `BalanceService` bean from Java code. No separate database server is needed.

## Using the service

Amounts use `long` integer minor units in one currency, such as cents.

With the project's runtime dependencies on the classpath, this example prints `1250` on a fresh database:

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

Use new transaction IDs for new operations. Rerunning these IDs does not apply the money again.

### Validation and failures

Identifiers must be non-null, nonblank, and at most 255 Java characters, without normalization. Malformed identifiers raise `IllegalArgumentException` and are not recorded.

Balances stay in `[0, Long.MAX_VALUE]`; overflow is checked before mutation. Business rejections raise `BalanceException` with a stable `code()`:

| Code | Meaning |
| --- | --- |
| `INVALID_AMOUNT` | Zero or negative amount |
| `ACCOUNT_NOT_FOUND` | Unknown account |
| `SAME_ACCOUNT_TRANSFER` | Source equals destination |
| `INSUFFICIENT_FUNDS` | Source cannot cover the amount |
| `BALANCE_OVERFLOW` | Result would exceed `Long.MAX_VALUE` |

For valid identifiers, the first committed outcome wins across operation types, even if retry parameters differ. History is checked before current business rules. Successful duplicates return normally; failed duplicates report the original failure, even after later funding.

Business failures are committed before being reported. Unexpected persistence errors trigger rollback, without a fabricated business rejection. If commit acknowledgement is lost, retry the same ID to discover the outcome. Inconsistent history raises `HistoryIntegrityException`.

## How it works

The main flow is `JpaBalanceService` (API) → `OperationCoordinator` (locks and transactions) → `BalanceStore` (JPA persistence). Tests mirror the production packages.

After identifier validation, each write:

1. Locks the transaction ID to serialize duplicates, even on different accounts.
2. Locks the affected accounts in ascending account-ID order.
3. Opens a `READ_COMMITTED` transaction, then replays history or validates and calculates all balances before mutation.
4. Commits balances and final history together, or rolls back, before releasing the locks.

Keyed `ReentrantLock`s let unrelated accounts progress independently while serializing conflicting work. Sorted account locks prevent opposite transfers from waiting in a cycle. Reads take only their account lock; no service path acquires a transaction-ID lock while holding an account lock. Together, these rules prevent deadlock in the service's lock hierarchy. Unused lock slots are reclaimed.

One database transaction makes transfers atomic; holding locks through commit keeps conflicting callers out until completion. Existing Spring transactions are rejected to prevent deferred commit after lock release. Separate `getBalance` calls are not a simultaneous multi-account snapshot.

**Scope and cost:** one JVM with shared singleton lock registries. Independent contexts, other processes, and direct database writers are outside the guarantee. Busy accounts serialize, including database I/O time. Performance has not been benchmarked.

## Persistence

File-backed H2 gives durable balances and replay without a database server. JPA maps entities and applies transactional updates; Flyway creates and seeds the schema reproducibly. Hibernate only validates it. This adds disk I/O and database-specific behavior; another DBMS needs fresh migration and transaction testing.

Data lives under `./data/finchbalance`, relative to the working directory, and is ignored by Git. H2 uses `sa` with an empty password and `WRITE_DELAY=0` to avoid delayed log writes; this is not a guarantee against every storage failure.

`accounts` holds ownership and balances; `transactions` holds final outcomes. Credit/debit writes one entry, successful transfer writes two, and failed transfer writes only a failed source entry.

[Flyway migrations](src/main/resources/db/migration) seed these accounts once; startup never resets them:

| Account | Owner | Opening balance |
| --- | --- | ---: |
| A | `user-a` | 100000 |
| B | `user-b` | 50000 |
| C | `user-c` | 0 |

Opening credits match the seeds; reserve `seed:opening:A` and `seed:opening:B`. Add new migrations instead of editing applied ones.

History is retained indefinitely, so storage grows. `BalanceReconciler.reconcile(accountId)` checks balances against successful history and reports discrepancies without repairing them.

## Tests and further reading

Tests use isolated H2 databases and real transactions. Coverage includes validation, duplicates, concurrent spending/transfers, independent progress, rollback, locks through commit, restart replay, 1000 mixed calls, and reconciliation. Tests never touch the normal local database.

[PLAN.md](PLAN.md) contains the detailed design; [TASKS.md](TASKS.md) records implementation and verification evidence. The implementation and tests were developed with Codex assistance.
