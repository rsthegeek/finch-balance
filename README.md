# Finchbalance

A Java service for crediting, debiting, and transferring account balances safely under concurrent calls. Built with Java 21, Spring Boot 4.0.9, Maven, JPA/Hibernate, Flyway, and file-backed H2.

## Build and run

Select JDK 21 with `JAVA_HOME`, then run from the project root:

```sh
./mvnw clean verify
java -jar target/finchbalance-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

The build runs the behavioral tests and checks Google Java Style with Spotless and Checkstyle. Format Java with `./mvnw spotless:apply`.

The JAR starts the API at `http://127.0.0.1:8080`. The `local` profile also enables the H2 console for inspecting data. No separate database server is needed. Access is bound to loopback for local testing; authentication is not implemented.

## HTTP API

| Method | Path | Request / response |
| --- | --- | --- |
| GET | `/api/accounts/{accountId}/balance` | Returns `{"accountId":"C","balance":1250}` |
| POST | `/api/credits` | JSON: `accountId`, `amount`, `transactionId` |
| POST | `/api/debits` | JSON: `accountId`, `amount`, `transactionId` |
| POST | `/api/transfers` | JSON: `sourceAccountId`, `destinationAccountId`, `amount`, `transactionId` |

Successful writes, including successful duplicate replays, return **204 No Content**. Read balances with GET rather than treating two separate reads as a simultaneous snapshot. On a fresh database, these requests leave C with 1250 minor units:

```sh
curl -i http://127.0.0.1:8080/api/credits \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"C","amount":1000,"transactionId":"api:credit"}'

curl -i http://127.0.0.1:8080/api/debits \
  -H 'Content-Type: application/json' \
  -d '{"accountId":"C","amount":250,"transactionId":"api:debit"}'

curl -i http://127.0.0.1:8080/api/transfers \
  -H 'Content-Type: application/json' \
  -d '{"sourceAccountId":"A","destinationAccountId":"C","amount":500,"transactionId":"api:transfer"}'

curl http://127.0.0.1:8080/api/accounts/C/balance
```

Amounts must be JSON integer numbers within the Java `long` range. Missing, null, fractional, string, and out-of-range amounts return **400** with `INVALID_REQUEST` and do not create history. Numeric zero/negative attempts reach business validation and persist `INVALID_AMOUNT`; retries consult history first.

Spring Bean Validation checks identifier shape and required amounts using `@NotBlank`, `@Size`, `@NotNull`, and `@Valid`. Business rules run in the service after replay lookup, so even a retry with a zero/negative amount can replay its original successful outcome.

Business errors return JSON such as `{"code":"INSUFFICIENT_FUNDS","message":"INSUFFICIENT_FUNDS"}`. Status codes are **400** for invalid amounts/self-transfers, **404** for unknown accounts, and **409** for insufficient funds/overflow. Integrity or persistence failures return **500** with a generic `INTERNAL_ERROR`, rather than a stored business rejection. A failed replay returns the original business code.

## Inspecting the database

While the app runs with the `local` profile, open `http://127.0.0.1:8080/h2-console/`.

Use these connection settings when starting from the project root:

| Setting | Value |
| --- | --- |
| Driver class | `org.h2.Driver` |
| JDBC URL | `jdbc:h2:file:./data/finchbalance;DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0;IFEXISTS=TRUE` |
| User name | `sa` |
| Password | Empty |

Use your configured datasource URL if you override it. `IFEXISTS=TRUE` avoids accidentally creating a different database. The console runs inside the application JVM and can inspect the existing database while the API runs. It is disabled without the `local` profile, following [Spring Boot's H2-console configuration](https://docs.spring.io/spring-boot/4.0/reference/data/sql.html#data.sql.h2-web-console).

Run these queries to see balances and committed history:

```sql
SELECT id, user_id, balance FROM accounts ORDER BY id;

SELECT transaction_id, entry_role, account_id, counterparty_account_id,
       amount, type, status, failure_code, created_at
FROM transactions
ORDER BY created_at, transaction_id, entry_role;
```

Use SELECT queries for inspection: SQL writes bypass the service's locks and history rules. Two separate queries while requests are running are not a simultaneous snapshot.

For IntelliJ IDEA's Database tool window, stop the app first and add an H2 file datasource with user `sa`, an empty password, and this URL (replace the path):

```text
jdbc:h2:file:/absolute/path/to/finchbalance/data/finchbalance;IFEXISTS=TRUE;ACCESS_MODE_DATA=r
```

Use the H2 driver version managed by this project's Maven dependencies. Omit the `.mv.db` filename suffix. H2's [read-only file access](https://h2database.github.io/html/features.html#custom_access_mode) supports inspection; a separate embedded connection cannot share the file while the app holds it. Disconnect the IDE datasource before restarting the app.

## Using the service

Amounts use `long` integer minor units in one currency, such as cents.

With the project's runtime dependencies on the classpath, this example prints `1250` on a fresh database:

```java
import me.fekri.finchbalance.FinchbalanceApplication;
import me.fekri.finchbalance.service.BalanceService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

public class BalanceExample {
  public static void main(String[] args) {
    try (var context = new SpringApplicationBuilder(FinchbalanceApplication.class)
        .web(WebApplicationType.NONE).run(args)) {
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

The HTTP flow is `BalanceController` → `BalanceService` / `JpaBalanceService` → `OperationCoordinator` (locks and transactions) → `BalanceStore` (JPA persistence). JSON request/response records live in `dto`, HTTP adapters in `controller`; tests mirror the production packages. Java callers can still use the service directly.

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

Controller tests use Spring's `MockMvcTester` with the real service and database. The H2 console's separate servlet is checked with a live HTTP test.

[PLAN.md](PLAN.md) contains the detailed design; [TASKS.md](TASKS.md) records implementation and verification evidence. The implementation and tests were developed with Codex assistance.
