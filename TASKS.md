# Finchbalance task checklist

Read [AGENTS.md](AGENTS.md) and [PLAN.md](PLAN.md) before implementation. Work in dependency order, selecting the next unchecked task unless the owner specifies another. Each task includes its own behavior tests; later integration tasks extend coverage rather than replacing earlier tests. Do not mark a task done merely because classes exist or mocked calls match expectations.

After a task, record its tests/commands and outcome under its evidence line. Prefer one coherent task per reviewable change. Documentation-only work uses content/link/command verification rather than artificial unit tests for prose. Checkboxes below describe current progress, not future intent.

## Starting point

- [x] **T00 — Initial Spring Boot project.** Java 25, Spring Boot 4.0.9, Maven Wrapper, JAR, properties configuration, JPA, H2, Flyway, and starter test dependencies.
  - Evidence: initial commit `2805197`; `./mvnw --batch-mode --no-transfer-progress verify` passed with one application-context test. No domain features are complete.

## Build and persistence foundation

- [x] **T01 — Enforce Google Java Style.** Depends on T00.
  - Add pinned Java-25-compatible Spotless/Google formatter and Google Checkstyle enforcement for production and test Java. Bind checks into `verify`; fail Checkstyle on warnings. Format the existing starter sources.
  - Verification: clean `./mvnw verify`; temporarily introduce a formatting violation in an isolated working copy and prove the check fails, then restore it. Similarly verify a selected non-formatting Checkstyle violation is rejected. Do not commit intentionally broken source or suppress violations.
  - Evidence: Spotless 3.10.3 / Google Java Format 1.30.0 and Checkstyle plugin 3.6.0 / engine 10.26.1 pinned. `./mvnw --batch-mode --no-transfer-progress spotless:apply verify` and `./mvnw --offline --batch-mode --no-transfer-progress clean verify` passed (1 test, no style violations). Isolated temporary-copy `verify` rejected an indentation violation via Spotless and a formatted test-source `bad_name` member via Checkstyle at warning severity; both probes restored. `git diff --check` passed.

- [x] **T02 — Configure persistent H2 and test isolation.** Depends on T01.
  - Add the properties from PLAN.md, a test configuration using distinct named in-memory databases, UTC `Clock`, and database-file ignore rules. Flyway is the sole initializer; Hibernate validates schema.
  - Tests: normal configuration selects file H2; integration tests cannot modify local `data/`; two isolated test databases do not share data. Verify chosen configuration with actual datasource connections, not only string assertions.
  - Evidence: `DatabaseConfigurationTest` passes 2 real-connection tests: normal properties open a file database with WRITE_DELAY=0 in a temporary child-JVM working directory; two independently named in-memory contexts do not share tables. UTC Clock and test profile configured; local database files ignored. Targeted `-Dtest=DatabaseConfigurationTest test`, then `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (3 tests, zero style violations). `git diff --check` passed.

- [x] **T03 — Create tables and constraints with Flyway V1.** Depends on T02.
  - Implement the agreed account/entry schema, composite key, ownership, role/type/status checks, and conditional business constraints. Allow unknown-account identifiers in failed entries; no account foreign keys.
  - Tests: real migrations create expected tables; negative balances, duplicate keys, and invalid successful rows are rejected; valid failed invalid-amount/self-transfer/unknown-account records are accepted. Cover null and nonblank failure-code rules.
  - Evidence: V1 creates exactly the agreed columns and composite key, with ownership, balance, enum, shape, outcome, and successful-transfer checks; no account foreign keys. `SchemaMigrationTest` passed 4 real-migration tests covering duplicate keys/owners, case sensitivity, malformed success rows, null/blank/unknown failure codes, and permitted failed unknown-account/invalid-amount/self-transfer rows. Targeted test, then `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (7 tests, zero style violations). `git diff --check` passed.

- [x] **T04 — Seed accounts and opening history with Flyway V2.** Depends on T03.
  - Seed A/B/C and nonzero opening-credit entries exactly as PLAN.md specifies. Keep applied migrations immutable.
  - Tests: exact seeded ownership/balances/history; zero-balance C has no invalid zero credit; close/reopen a temporary file database and rerun migrations without duplicates or resetting a changed balance. Commit fixture changes before closing.
  - Evidence: V2 seeds A/B/C ownership and balances plus exactly two positive opening SINGLE credits. `SeedMigrationTest` passed exact seed/history checks and a sequential temporary-file restart after a committed balance change; V1/V2 each applied once without reseeding. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (9 tests, zero style violations). `git diff --check` passed; V1 unchanged.

- [x] **T05 — Map JPA entities and composite identity.** Depends on T03.
  - Add `Account`, `TransactionEntry`, embedded entry identity, and string-mapped role/type/status/failure-code enums. Scalar account identifiers; JPA-compatible constructors; no optimistic version field. Preserve the schema.
  - Tests: persist/flush/clear/reload every valid entry form, null counterparty/failure-code behavior, timestamp instant preservation, composite-ID equality/hash behavior, and mapping validation against real migrations.
  - Evidence: explicit JPA field mappings validate against unchanged V1/V2; enums use VARCHAR strings, history is immutable with no setters, and scalar account IDs permit unknown-account failures. `JpaMappingTest` passed all valid forms through persist/flush/clear and fresh-context reload, null fields, microsecond Instant round trips, account ownership/boundary balance, and composite-ID hash collection behavior. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (11 tests, zero style violations). `git diff --check` passed.

- [x] **T06 — Implement focused JPA persistence operations.** Depends on T05.
  - Add account lookup and original-transaction-ID entry lookup plus explicit append-only entry persistence. Managed account changes use dirty tracking. Avoid save/merge overwriting caller-assigned history IDs.
  - Tests: real committed lookup/update round trips; all entries for one original ID are found; unrelated IDs are excluded; attempted duplicate append fails and cannot overwrite original history. Read assertions using a fresh persistence context.
  - Evidence: `BalanceStore` provides parameterized original-ID lookup, managed account lookup, and append via EntityManager.persist only. `BalanceStoreTest` verifies real committed dirty tracking, complete pair lookup with unrelated-ID exclusion, and duplicate-append failure rolling back an account change while preserving original history, all inspected in fresh transactions. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (13 tests, zero style violations). `git diff --check` passed.

## Behavior and coordination primitives

- [x] **T07 — Define service contract and request validation.** Depends on T01.
  - Add the exact `BalanceService` interface, small immutable internal requests/outcomes, stable business exceptions, and identifier-envelope validation. Keep amount/account-state validation inside the eventual transaction path after duplicate lookup.
  - Tests: null/blank/overlong identifiers rejected; 255-character IDs accepted; IDs are not normalized; every request type retains its fields and failure outcomes preserve stable codes. Avoid trivial getters-only tests.
  - Evidence: exact BalanceService API, immutable operation envelope/final outcome, BalanceException stable codes, and identifier-only validation added. `RequestValidationTest` covers every identifier position with null/blank/overlong values, 255-character boundaries, unchanged identifiers, deferred invalid amounts/self-transfer, and every failure-code report. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (16 tests, zero style violations). `git diff --check` passed.

- [x] **T08 — Implement safe keyed ReentrantLock leases.** Depends on T01.
  - Implement reference-counted slots covering owners/waiters, atomic slot lifecycle, and owning-thread lease release. Do not block inside map computations or leak one slot per transaction forever.
  - Tests: same-key mutual exclusion, distinct-key progress, queued waiter handoff, cleanup after normal/exceptional callback execution, and acquire/release/reacquire interleavings. Coordinate real threads; assertions must detect overlapping critical sections. Verify safe cleanup while a waiter exists.
  - Evidence: KeyedLocks retains reference-counted slots before uninterruptible acquisition and removes them only after unlock and the last reference release. KeyedLocksTest coordinates 1600 same-key sections, queued-waiter/newcomer handoff, distinct-key progress, exception cleanup, wrong-thread release, double close, and final zero retained slots. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (19 tests, zero style violations). `git diff --check` passed.

- [x] **T09 — Implement ordered lock acquisition.** Depends on T07, T08.
  - Wire separate shared transaction/account registries. Acquire original-ID first, then distinct accounts sorted consistently; release in reverse order with structured cleanup. Handle self-transfer by deduplication.
  - Tests: reversed transfer arguments use the same acquisition order, self-transfer acquires once, exceptions release every acquired lease, identical original IDs serialize even for different operation types, and unrelated IDs/accounts progress.
  - Evidence: Separate singleton transaction/account registries and structured OperationLocks enforce original-ID first, sorted distinct accounts, and reverse release. OperationLocksTest verifies both transfer directions, self-transfer deduplication, callback/partial-acquisition cleanup, cross-operation same-ID exclusion, and unrelated progress with bounded real threads. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (22 tests, zero style violations). `git diff --check` passed.

- [x] **T10 — Decode and validate stored outcomes.** Depends on T06, T07.
  - Translate complete entry groups into success or the original failure. Accept one SINGLE, a matching successful transfer pair, or one failed SOURCE. Validate pair amounts, accounts/counterparties, type, and status. Do not depend on query row order or incoming retry payloads.
  - Tests: each valid group decodes; reversed pair order works; incomplete destination-only/source-success groups, mismatched pairs, and mixed SINGLE/transfer groups produce integrity errors. They must never trigger new execution.
  - Evidence: StoredOutcomes decodes valid SINGLE successes/failures, failed transfer SOURCE, and matching successful pairs independent of row order and retry payload. StoredOutcomesTest rejects incomplete, mixed, mismatched amounts/accounts/counterparties/types/statuses/IDs/timestamps and invalid row shapes with HistoryIntegrityException; no execution path exists in the decoder. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (24 tests, zero style violations). `git diff --check` passed.

- [x] **T11 — Implement exact balance calculations and business checks.** Depends on T07.
  - Implement deterministic validation ordering and overflow-safe credit/debit/transfer calculations without mutating entities before all checks pass.
  - Tests: zero/negative amounts including `Long.MIN_VALUE`, debit exactly equal to balance, insufficient funds, unknown source/destination ordering, same-account transfer, `Long.MAX_VALUE` boundary, and destination overflow. Assert original state remains unchanged on rejection.
  - Evidence: BalanceChange calculates both final balances before mutation using validated subtraction and Math.addExact. BalanceChangeTest covers deterministic amount/self-transfer/existence/funds/overflow ordering, Long.MIN_VALUE attempted amounts, exact-balance debit, Long.MAX_VALUE success boundaries, destination overflow, and unchanged original accounts. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (28 tests, zero style violations). `git diff --check` passed.

## Service implementation

- [x] **T12 — Connect Java locks to a fresh JPA transaction.** Depends on T06, T09, T10, T11.
  - Use `TransactionTemplate`/JPA transaction manager with READ_COMMITTED. Reject ambient transactions. Hold leases through actual commit/rollback; duplicate lookup precedes business validation. Return recorded business outcomes from the callback before public exception translation.
  - Tests: real transaction boundaries commit final business failures, roll back unexpected exceptions, and reject ambient transactions before writes. Assert entry/balance state after transaction completion, not only callback invocation. Keep any shared executor abstraction small.
  - Evidence: OperationCoordinator rejects ambient transactions, checks stored groups before invoking new work, and holds structured leases around READ_COMMITTED TransactionTemplate completion with the JPA manager. OperationCoordinatorTest observes committed failure before public exception reporting, cross-type replay, actual post-flush rollback, ambient rejection without writes, and corrupt-group rejection without reexecution in fresh transactions. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (31 tests, zero style violations). `git diff --check` passed.

- [x] **T13 — Implement credit and getBalance.** Depends on T04, T12.
  - Wire the public credit path, successful/failed SINGLE entries, and account-locked balance reads. Sample creation instants from injected Clock.
  - Tests: credit changes balance and appends exactly one correct entry; invalid amount/unknown account/overflow persist final failures without balance changes; getBalance returns the committed value and rejects unknown accounts; sequential credit duplicates replay without new entries.
  - Evidence: Credit and account-locked balance reads are wired through the shared coordinator with injected Clock timestamps. CreditServiceTest verifies exact SINGLE contents, changed-payload success/failure replay, invalid-amount/unknown-account/overflow persistence without balance changes, committed reads, malformed envelopes without history, and ambient-read rejection. Debit/transfer remain explicit T14/T15 stubs in this commit. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (34 tests, zero style violations). `git diff --check` passed.

- [x] **T14 — Implement debit.** Depends on T13.
  - Wire debit success/rejection and SINGLE history through the shared coordinator.
  - Tests: normal/exact-balance debit, insufficient funds, invalid amount, unknown account, sequential duplicates, and failure replay after later funding. Two gated concurrent unique debits of 700 from 1000 yield one success, one final failure, balance 300, and two logical outcomes.
  - Evidence: Debit uses the shared single-entry path. DebitServiceTest checks exact history, normal/exact-balance debit, changed-payload and cross-operation success replay, invalid amounts, unknown accounts, final insufficient-funds replay after funding, and a two-caller gated 700-from-1000 race yielding one success, one persisted failure, and balance 300. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (37 tests, zero style violations). `git diff --check` passed.

- [x] **T15 — Implement atomic transfer.** Depends on T14.
  - Wire two successful entries with reciprocal counterparties/equal amounts/shared Instant, or only one failed source entry preserving the intended destination. Commit both balances and entries together.
  - Tests: success and duplicate effects; unknown source/destination; insufficient funds; self-transfer; invalid amount; destination overflow. Assert exact balances, roles, counterparties, statuses, timestamps, and row counts; every business failure leaves both balances unchanged.
  - Evidence: Transfer calculates both balances before mutation and commits reciprocal SOURCE/DESTINATION entries sharing one sampled Instant, or only the failed SOURCE with intended destination. TransferServiceTest verifies exact balances and complete entry contents, changed-payload duplicates, unknown source/destination, insufficient funds, self-transfer, zero/negative/Long.MIN_VALUE attempts, destination overflow, and Long.MAX_VALUE full-balance transfer. All business rejections preserve both balances. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (40 tests, zero style violations). `git diff --check` passed.

## Adversarial integration tests

- [x] **T16 — Prove concurrent original-ID idempotency.** Depends on T15.
  - Test concurrent duplicates for credit, debit, successful transfer, and failed transfer, including a replay after account state changes.
  - Tests: exactly one SINGLE/two successful transfer rows/one failed transfer SOURCE as appropriate; every caller observes the recorded outcome. Race credit versus transfer with the same original ID and prove only one role group commits, accepting either scheduling winner. No payload comparison or forced winner.
  - Evidence: ConcurrentIdempotencyTest coordinates real callers for credit/debit/successful-transfer/failed-transfer duplicates and repeats a stored failure after funding. Every caller result is observed; exact balances and entry cardinalities are asserted. A gated credit-versus-transfer original-ID race accepts either scheduling winner and proves only its complete role group commits, with registry cleanup. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (42 tests, zero style violations). `git diff --check` passed.

- [x] **T17 — Prove rollback and lock-through-commit behavior.** Depends on T15.
  - Add controlled faults around actual persistence collaborators and a controlled transaction-completion observer. Keep fault hooks in test wiring rather than the public service API.
  - Tests: force a flush of the first account change, then fail; separately fail while inserting transfer entries. Real rollback restores both balances and removes all attempted entries. A conflicting request cannot pass account locking before commit/rollback finishes. Verify cleanup and read final state in a new persistence context.
  - Evidence: Test-only PersistenceFaults wraps real managed collaborators. RollbackAndCommitLockTest proves a flushed first account change and separately a flushed first transfer entry are rolled back with no fabricated outcome, then same-ID retry succeeds. Completion observers cover commit and a before-commit fault/rollback; conflicting writes and getBalance stay parked on account locking until completion is released. Fresh transactions verify exact balances/history and empty lock registries. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (44 tests, zero style violations). `git diff --check` passed.

- [x] **T18 — Prove deadlock avoidance and independent progress.** Depends on T15.
  - Exercise opposing and overlapping transfers plus operations on distinct accounts with shared real lock registries.
  - Tests: bounded completion, correct outcome counts, exact expected balances, and conserved funds. While A is deliberately blocked, a distinct transaction on B completes before A is released. Verify a blocked duplicate does not prevent unrelated work.
  - Evidence: ConcurrentProgressTest completes 200 opposing and 300 overlapping unique transfers with bounded callers, exact expected balances/entry counts, and conserved funds. A deliberate A lock blocks a request and its same-ID duplicate while a distinct B operation commits before A is released; all futures are observed and registries clean up. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (46 tests, zero style violations). `git diff --check` passed.

- [x] **T19 — Prove restart durability and retention.** Depends on T16, T17.
  - Run real service contexts sequentially against the same temporary file-backed H2 database. Close the first context/pool completely; the second uses fresh lock registries.
  - Tests: successful credit/debit/transfer and business-failure replay after restart; balances/history are unchanged by duplicates; seeds are not reset. Advance the injected Clock beyond 72 hours and confirm replay still works. Confirm unexpected rolled-back attempts have no fabricated final outcome.
  - Evidence: RestartDurabilityTest closes the first context and confirms Hikari pool closure before reopening the same temporary file database with fresh registries and Clock +96 hours. Credit/debit/transfer successes and failed transfer/debit outcomes replay unchanged after funding; full detached history and exact balances match, seeds are retained, and a flushed rolled-back attempt has no outcome and can execute after restart. Targeted tests and `./mvnw --offline --batch-mode --no-transfer-progress spotless:apply verify` passed (47 tests, zero style violations). `git diff --check` passed.

- [ ] **T20 — Verify mixed concurrent workloads.** Depends on T16, T18.
  - Add bounded batches of unique/duplicate operations on one and multiple accounts. Use sufficient funds for cases needing order-independent exact totals; keep insufficient-funds races separately specified.
  - Tests: observe every worker result/exception; match balances, successful/failed logical operation counts, and entry cardinality to independently derived expectations. Include a workload of about 1000 operations without requiring 1000 platform threads. Do not assert unmeasured throughput targets.
  - Evidence: pending.

- [ ] **T21 — Add and verify balance reconciliation.** Depends on T04, T15.
  - Sum successful entry effects by role/type using a wide decimal accumulator, including opening entries. Add the account-history index if justified by the actual query.
  - Tests: reconcile A/B/C and changed accounts after workers finish, exclude failed entries, include both transfer directions, and detect a deliberately inconsistent account fixture. Include lifetime turnover beyond `Long.MAX_VALUE` while valid individual balances remain within range. Compare exact values under a stable view.
  - Evidence: pending.

## Review and delivery

- [ ] **T22 — Write the submission README and audit completion.** Depends on T19, T20, T21.
  - Document actual run/test commands, Java 25 selection, API usage, schema/seed data, lock order, database commit boundary, replay/failure semantics, single-JVM scope, retention/storage cost, and migration limitations. Explain AI-assisted work in terms the owner can defend; invent no performance results.
  - Verification: execute documented build commands from a clean checkout; all behavior tests and style checks pass under `./mvnw verify`; confirm JAR packaging and clean tracked outputs. Review every task's evidence against its acceptance tests and reconcile README claims with implementation. Do not add a dummy test just for README prose.
  - Evidence: pending.

## Test discipline for every task

- Production behavior and its tests belong in the same task; do not defer all meaningful tests to T16-T21.
- Write tests that would fail for the plausible bug: duplicate effects, missing entry, stale read, partial commit, failure accidentally rolled back, early unlock, or lock-slot split.
- Prefer observable state to private-method or invocation-count assertions. Use real H2 for mappings, constraints, commit/rollback, migration, and restart semantics.
- Use committed fixtures, isolated databases, fresh persistence contexts for final assertions, and no enclosing test transaction for concurrent/restart/commit tests.
- Use latches/barriers for controlled scheduling, bounded futures and executor cleanup. No arbitrary sleeps. A barrier needing more parties than the executor can run can deadlock the test.
- Run targeted tests while editing, then `./mvnw spotless:apply` and `./mvnw verify` once tooling exists. Do not disable, suppress, or skip checks to finish a task.
- Record exact verification results and limitations. Do not check off work whose required tests failed or could not run.
