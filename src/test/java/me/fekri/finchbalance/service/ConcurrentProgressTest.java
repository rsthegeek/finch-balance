package me.fekri.finchbalance.service;

import static me.fekri.finchbalance.support.ConcurrentTestSupport.await;
import static me.fekri.finchbalance.support.ConcurrentTestSupport.awaitWaiting;
import static me.fekri.finchbalance.support.ConcurrentTestSupport.shutdown;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import me.fekri.finchbalance.concurrency.KeyedLocks;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ConcurrentProgressTest {
  @Test
  void opposingAndOverlappingTransfersCompleteWithExactBalancesAndConservedFunds()
      throws Exception {
    try (var fixture = new ServiceFixture()) {
      fixture.service.credit("C", 1000, "fund-C");
      assertThat(
              ConcurrentServiceCalls.run(
                  200,
                  index ->
                      () ->
                          fixture.service.transfer(
                              index % 2 == 0 ? "A" : "B",
                              index % 2 == 0 ? "B" : "A",
                              1,
                              "opposite:" + index)))
          .containsOnlyNulls();
      assertBalances(fixture);
      String[] accounts = {"A", "B", "C"};
      assertThat(
              ConcurrentServiceCalls.run(
                  300,
                  index ->
                      () ->
                          fixture.service.transfer(
                              accounts[index % 3],
                              accounts[(index + 1) % 3],
                              1,
                              "overlap:" + index)))
          .containsOnlyNulls();
      assertBalances(fixture);
      var jdbc = new JdbcTemplate(fixture.context.getBean(DataSource.class));
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM transactions WHERE transaction_id LIKE 'opposite:%'",
                  Integer.class))
          .isEqualTo(400);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM transactions WHERE transaction_id LIKE 'overlap:%'",
                  Integer.class))
          .isEqualTo(600);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM transactions WHERE status = 'FAILED'", Integer.class))
          .isZero();
    }
  }

  @Test
  void blockedAccountAndBlockedDuplicateDoNotPreventDistinctAccountProgress() throws Exception {
    try (var fixture = new ServiceFixture()) {
      var accounts = fixture.context.getBean("accountLocks", KeyedLocks.class);
      var held = accounts.acquire("A");
      boolean released = false;
      var executor = Executors.newFixedThreadPool(3);
      var firstAttempting = new CountDownLatch(1);
      var duplicateAttempting = new CountDownLatch(1);
      var firstThread = new AtomicReference<Thread>();
      var duplicateThread = new AtomicReference<Thread>();
      try {
        final var first =
            executor.submit(
                () -> {
                  firstThread.set(Thread.currentThread());
                  firstAttempting.countDown();
                  fixture.service.credit("A", 10, "blocked");
                });
        await(firstAttempting);
        awaitWaiting(firstThread.get());
        final var duplicate =
            executor.submit(
                () -> {
                  duplicateThread.set(Thread.currentThread());
                  duplicateAttempting.countDown();
                  fixture.service.credit("A", 10, "blocked");
                });
        await(duplicateAttempting);
        awaitWaiting(duplicateThread.get());
        executor
            .submit(() -> fixture.service.credit("B", 1, "independent"))
            .get(10, TimeUnit.SECONDS);
        assertThat(fixture.entries("independent")).hasSize(1);
        assertThat(first).isNotDone();
        assertThat(duplicate).isNotDone();
        held.close();
        released = true;
        first.get(10, TimeUnit.SECONDS);
        duplicate.get(10, TimeUnit.SECONDS);
        assertThat(fixture.service.getBalance("A")).isEqualTo(100010);
        assertThat(fixture.service.getBalance("B")).isEqualTo(50001);
        assertThat(fixture.entries("blocked")).hasSize(1);
        assertThat(accounts.slotCount()).isZero();
        assertThat(fixture.context.getBean("transactionLocks", KeyedLocks.class).slotCount())
            .isZero();
      } finally {
        if (!released) {
          held.close();
        }
        shutdown(executor);
      }
    }
  }

  private static void assertBalances(ServiceFixture fixture) {
    long a = fixture.service.getBalance("A");
    long b = fixture.service.getBalance("B");
    long c = fixture.service.getBalance("C");
    assertThat(a).isEqualTo(100000);
    assertThat(b).isEqualTo(50000);
    assertThat(c).isEqualTo(1000);
    assertThat(a + b + c).isEqualTo(151000);
  }
}
