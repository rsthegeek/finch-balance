# Finchbalance: instructions for Codex sessions

## Start here

- Read [PLAN.md](PLAN.md) for the accepted behavior/architecture and [TASKS.md](TASKS.md) for task dependencies, tests, and progress before implementing changes.
- Inspect the working tree and relevant existing code. Preserve unrelated owner changes. Use the task requested by the owner; otherwise progress to the next unchecked task with prerequisites satisfied.
- The owner expects to understand and defend the implementation in a senior Java review. Keep code small, explicit, and explainable. Explain material design changes rather than silently replacing accepted choices.
- Current implementation starts as a Spring Boot scaffold. Do not claim migrations, locking, or business operations exist until the task evidence supports that claim.

## Fixed project choices

- `me.fekri:finchbalance`; base package `me.fekri.finchbalance`; Spring Boot 4.0.9; Java 25; Maven Wrapper; JAR; `.properties` configuration.
- Spring Data JPA/Hibernate with `jakarta.persistence` imports. File-backed H2 for normal execution; isolated H2 databases for tests. Flyway alone creates schema/seeds.
- Java `ReentrantLock` for original transaction IDs and account IDs. Do not introduce optimistic locking, `@Version`, automatic retry loops, a global execution lock, or a second account concurrency protocol without an owner-requested design change.
- One JVM with shared singleton lock registries. Do not imply correctness across independent processes or direct database writers.
- Preserve the exact two-table schema and entry semantics in PLAN.md. No concatenated transaction-ID suffixes, payload comparison on replay, or JSON metadata replacing typed columns.
- No REST/Redis/Kafka/Docker/authentication implementation or generic framework unless requested. Keep dependencies and build plugins limited to actual needs.

## Google Java Style

- Follow the [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html) for every production and test Java source.
- Use two-space block indentation, braces for control flow, explicit imports, consistent import order, and formatter-managed wrapping. Do not use wildcard imports.
- Use meaningful names and Javadoc for public types and nontrivial public methods. Comments explain invariants or reasoning, not obvious syntax.
- Pin a Java-25-compatible Google formatter through Spotless. T01 also adds Google Checkstyle enforcement for main and test sources, with warnings failing the build. Do not claim these plugins exist before T01 is implemented.
- Once configured, use `./mvnw spotless:apply` and run `./mvnw verify`. Never suppress new violations, weaken checks, exclude changed source, or skip tests to obtain a green build.
- Use constructor injection, string-mapped enums, and explicit persistence mappings. Avoid Lombok and generated accessor boilerplate tooling unless requested. Keep domain outcomes immutable; managed JPA entities need their normal JPA-compatible shape.

## Business invariants

- All amounts are integer minor units in one currency. Successful amounts are positive; account balances stay in `[0, Long.MAX_VALUE]`. Detect overflow before mutation.
- Validate identifier shape before locking. Malformed identifier requests are outside persisted idempotency. For valid identifiers, check stored outcomes before current business rules.
- The first committed outcome wins for an original transaction ID across all operation types. Duplicate success returns normally; duplicate failure reports the stored failure. Do not reapply money or add entries.
- Store expected business failures as final FAILED outcomes. Do not reevaluate them after funding/account-state changes. Unexpected persistence errors roll back; they are not fabricated final business rejections.
- Credit/debit produce one SINGLE entry. Successful transfer produces a matching SOURCE/DESTINATION pair. Failed transfer produces only a failed SOURCE retaining the intended destination as counterparty.
- Keep failure code and counterparty as dedicated fields. Scalar submitted account IDs must permit failed unknown-account records; do not add account foreign keys or mandatory JPA associations that prevent this.
- Never repair an incomplete/inconsistent committed entry group by reexecuting it. Report an integrity problem.
- Preserve opening-credit history and retain transaction history indefinitely in this version. No three-day expiry or scheduled cleanup that permits ID reuse or breaks reconstruction.

## Lock and commit discipline

1. Reject ambient database transactions at the outer coordinator.
2. Acquire the original transaction-ID lock first.
3. Acquire distinct account locks in consistent account-ID order.
4. Only then open a READ_COMMITTED transaction using `TransactionTemplate` and the JPA transaction manager.
5. Look up history, validate, mutate managed account entities, and append final history in that one database transaction.
6. Finish the actual JPA flush and database commit/rollback before releasing account locks and then the transaction lock.
7. Translate a committed business failure into its public exception outside the transaction callback.

- No `@Transactional` on the outer locking method and no `REQUIRES_NEW` splitting account/history writes. Joining an ambient transaction could defer commit beyond lock release.
- `persist`/`save`/`flush` is not a commit. Handle commit-time failures as real failures and retain locks through transaction completion.
- Persist history append-only; prevent caller-assigned-ID save/merge from updating old entries. Do not expose setters that encourage rewriting terminal history.
- Validate every expected failure and calculate both resulting transfer balances before changing either account. Both successful entries share one sampled Instant from an injected Clock.
- Account locking also covers `getBalance`; it must not reverse the lock hierarchy. Two separate reads are not a simultaneous multi-account snapshot.
- Keyed-lock slots must count owners and waiters before lock acquisition. Never wait inside map computations or naively remove a slot after unlock. Do not leak one retained lock per historical transaction.

## Tests required with implementation

- Every code, behavior, configuration, or migration task includes meaningful tests/acceptance checks in the same change, as listed in TASKS.md. Prefer tests that expose an incorrect implementation over getter tests or mocks that merely repeat production call structure.
- Verify externally visible balances, entry counts/contents, business outcomes, and commit/rollback semantics. A context-load test alone does not verify a feature.
- Use real H2 and real transactions for schema, JPA mapping, atomicity, failure persistence, and restart behavior. Narrow test doubles can introduce faults or scheduling gates around real collaborators; mocked persistence alone proves none of those guarantees.
- Concurrent tests must coordinate real callers with latches/barriers, observe every future/result, use bounded waits, and clean up in `finally`. Avoid sleeps, flaky throughput assertions, and barriers requiring more runnable parties than the pool supplies.
- Concurrent/restart/commit tests must not run inside an enclosing test transaction. Commit fixtures first and inspect final state with a fresh persistence context.
- Restart tests close the first context/pool and open a new context at the same temporary file database with fresh lock registries. Never touch the owner's local database or use destructive cleanup against it.
- Cover boundary values, negative paths, duplicate failure replay, cross-operation original-ID races, opposite transfers, unrelated-account progress, partial-write rollback, and early-unlock hazards as their tasks are implemented.
- Documentation-only changes use content/link consistency checks rather than artificial unit tests. Any executable examples or altered build commands must be verified appropriately.

## Flyway, configuration, and reproducibility

- Use versioned migrations under `src/main/resources/db/migration`. Never modify applied migrations. Set Hibernate to validate, never create/update schema. Do not add secondary initialization scripts or seeding runners.
- Seed account balances and matching opening-credit entries together. Startup must not reset data. Keep account ownership explicit without building a users/auth subsystem.
- Use properties configuration, injected UTC Clock, parameterized queries where custom SQL is necessary, and temporary test databases. Do not commit database files, credentials, build outputs, or machine-specific paths.
- Keep dependency versions compatible with the pinned Boot parent; pin independent formatting/checking plugin versions. Do not upgrade Java/Boot merely to fix an implementation issue.

## Definition of done and handoff

- Implement the selected task and its real acceptance tests; run targeted tests while editing, then the required formatting and full `./mvnw verify` for implementation changes.
- Inspect the diff and check `git diff --check`. Update the selected TASKS.md checkbox/evidence only when its requirements passed. Report blocked checks honestly; do not claim success from an earlier unrelated run.
- Keep PLAN.md and README accurate when behavior changes. Do not mark later tasks complete based only on shared scaffolding.
- Summarize what changed, how it was verified, and any remaining limitation in plain language. Make no unmeasured performance or multi-instance guarantees.
- Do not create commits or publish unless the owner requests it. An earlier request for the initial scaffold commit does not authorize automatic commits for every task.
