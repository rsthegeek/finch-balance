package me.fekri.finchbalance;

import static me.fekri.finchbalance.ConcurrentTestSupport.await;
import static me.fekri.finchbalance.ConcurrentTestSupport.shutdown;
import static me.fekri.finchbalance.ServiceFixture.rejects;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DebitServiceTest {
  @Test
  void normalAndExactBalanceDebitAppendOnceAndSuccessfulDuplicatesReplay() {
    try (var fixture = new ServiceFixture()) {
      fixture.service.debit("A", 1000, "debit");
      fixture.service.debit("A", 99999, "debit");
      fixture.service.credit("missing", -1, "debit");
      assertThat(fixture.service.getBalance("A")).isEqualTo(99000);
      assertThat(fixture.entries("debit"))
          .singleElement()
          .usingRecursiveComparison()
          .isEqualTo(
              new TransactionEntry(
                  "debit",
                  EntryRole.SINGLE,
                  "A",
                  null,
                  1000,
                  OperationType.DEBIT,
                  EntryStatus.SUCCEEDED,
                  null,
                  ServiceFixture.NOW));
      fixture.service.debit("A", 99000, "exact");
      assertThat(fixture.service.getBalance("A")).isZero();
      assertThat(fixture.entries("exact")).hasSize(1);
    }
  }

  @Test
  void failuresRemainFinalAfterFundingAndNewIdsCanSucceed() {
    try (var fixture = new ServiceFixture()) {
      fixture.setBalance("A", 500);
      rejects(
          FailureCode.INSUFFICIENT_FUNDS, () -> fixture.service.debit("A", 700, "insufficient"));
      fixture.service.credit("A", 1000, "fund");
      rejects(FailureCode.INSUFFICIENT_FUNDS, () -> fixture.service.debit("A", 1, "insufficient"));
      assertThat(fixture.service.getBalance("A")).isEqualTo(1500);
      assertThat(fixture.entries("insufficient"))
          .singleElement()
          .usingRecursiveComparison()
          .isEqualTo(
              new TransactionEntry(
                  "insufficient",
                  EntryRole.SINGLE,
                  "A",
                  null,
                  700,
                  OperationType.DEBIT,
                  EntryStatus.FAILED,
                  FailureCode.INSUFFICIENT_FUNDS,
                  ServiceFixture.NOW));
      fixture.service.debit("A", 700, "new-attempt");
      assertThat(fixture.service.getBalance("A")).isEqualTo(800);
      for (long amount : new long[] {0, -1, Long.MIN_VALUE}) {
        String id = "invalid:" + amount;
        rejects(FailureCode.INVALID_AMOUNT, () -> fixture.service.debit("A", amount, id));
        assertThat(fixture.entries(id))
            .singleElement()
            .satisfies(
                entry -> {
                  assertThat(entry.amount()).isEqualTo(amount);
                  assertThat(entry.status()).isEqualTo(EntryStatus.FAILED);
                  assertThat(entry.failureCode()).isEqualTo(FailureCode.INVALID_AMOUNT);
                });
      }
      rejects(FailureCode.ACCOUNT_NOT_FOUND, () -> fixture.service.debit("missing", 1, "unknown"));
      assertThat(fixture.entries("unknown"))
          .singleElement()
          .satisfies(entry -> assertThat(entry.accountId()).isEqualTo("missing"));
      assertThat(fixture.service.getBalance("A")).isEqualTo(800);
    }
  }

  @Test
  void concurrentUniqueDebitsCannotBothSpendTheSameFunds() throws Exception {
    try (var fixture = new ServiceFixture()) {
      fixture.setBalance("A", 1000);
      var executor = Executors.newFixedThreadPool(2);
      var ready = new CountDownLatch(2);
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<FailureCode>>();
      try {
        for (int i = 0; i < 2; i++) {
          final String id = "race:" + i;
          futures.add(
              executor.submit(
                  () -> {
                    ready.countDown();
                    await(start);
                    try {
                      fixture.service.debit("A", 700, id);
                      return null;
                    } catch (BalanceException failure) {
                      return failure.code();
                    }
                  }));
        }
        await(ready);
        start.countDown();
        var results = new ArrayList<FailureCode>();
        for (var future : futures) {
          results.add(future.get(10, TimeUnit.SECONDS));
        }
        assertThat(results).containsExactlyInAnyOrder(null, FailureCode.INSUFFICIENT_FUNDS);
        assertThat(fixture.service.getBalance("A")).isEqualTo(300);
        var entries = new ArrayList<>(fixture.entries("race:0"));
        entries.addAll(fixture.entries("race:1"));
        assertThat(entries)
            .hasSize(2)
            .extracting(TransactionEntry::status)
            .containsExactlyInAnyOrder(EntryStatus.SUCCEEDED, EntryStatus.FAILED);
      } finally {
        start.countDown();
        shutdown(executor);
      }
    }
  }
}
