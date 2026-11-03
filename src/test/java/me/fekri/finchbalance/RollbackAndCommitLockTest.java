package me.fekri.finchbalance;

import static me.fekri.finchbalance.ConcurrentTestSupport.await;
import static me.fekri.finchbalance.ConcurrentTestSupport.awaitWaiting;
import static me.fekri.finchbalance.ConcurrentTestSupport.shutdown;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;

class RollbackAndCommitLockTest {
  @Test
  void flushedFirstAccountAndFlushedFirstEntryFaultsRollBackTheWholeTransfer() {
    for (var mode :
        List.of(PersistenceFaults.Mode.FIRST_ACCOUNT, PersistenceFaults.Mode.FIRST_ENTRY)) {
      try (var fixture = fixture()) {
        var control = fixture.context.getBean(PersistenceFaults.Control.class);
        control.next.set(mode);
        assertThatThrownBy(() -> fixture.service.transfer("A", "B", 10, "partial"))
            .isInstanceOf(PersistenceFaults.ControlledFailure.class);
        if (mode == PersistenceFaults.Mode.FIRST_ACCOUNT) {
          assertThat(control.balanceAfterFlush).hasValue(99990);
        } else {
          assertThat(control.entriesAfterFlush).hasValue(1);
        }
        assertThat(fixture.service.getBalance("A")).isEqualTo(100000);
        assertThat(fixture.service.getBalance("B")).isEqualTo(50000);
        assertThat(fixture.entries("partial")).isEmpty();
        assertCleanLocks(fixture);
        fixture.service.transfer("A", "B", 10, "partial");
        assertThat(fixture.entries("partial")).hasSize(2);
      }
    }
  }

  @Test
  void conflictingWriteAndReadWaitThroughActualCommitAndRollbackCompletion() throws Exception {
    for (boolean rollback : new boolean[] {false, true}) {
      try (var fixture = fixture()) {
        var control = fixture.context.getBean(PersistenceFaults.Control.class);
        control.next.set(
            rollback
                ? PersistenceFaults.Mode.OBSERVE_ROLLBACK
                : PersistenceFaults.Mode.OBSERVE_COMMIT);
        var executor = Executors.newFixedThreadPool(3);
        var writeAttempting = new CountDownLatch(1);
        var readAttempting = new CountDownLatch(1);
        var writerThread = new AtomicReference<Thread>();
        var readerThread = new AtomicReference<Thread>();
        try {
          final var first =
              executor.submit(() -> fixture.service.transfer("A", "B", 10, "completion"));
          await(control.completionReached);
          assertThat(control.completionStatus)
              .hasValue(
                  rollback
                      ? TransactionSynchronization.STATUS_ROLLED_BACK
                      : TransactionSynchronization.STATUS_COMMITTED);
          final var writer =
              executor.submit(
                  () -> {
                    writerThread.set(Thread.currentThread());
                    writeAttempting.countDown();
                    fixture.service.credit("A", 1, "conflict");
                  });
          final var reader =
              executor.submit(
                  () -> {
                    readerThread.set(Thread.currentThread());
                    readAttempting.countDown();
                    return fixture.service.getBalance("A");
                  });
          await(writeAttempting);
          await(readAttempting);
          awaitWaiting(writerThread.get());
          awaitWaiting(readerThread.get());
          assertThat(writer).isNotDone();
          assertThat(reader).isNotDone();
          control.releaseCompletion.countDown();
          if (rollback) {
            assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(PersistenceFaults.ControlledFailure.class);
          } else {
            first.get(10, TimeUnit.SECONDS);
          }
          writer.get(10, TimeUnit.SECONDS);
          long read = reader.get(10, TimeUnit.SECONDS);
          long beforeCredit = rollback ? 100000 : 99990;
          assertThat(read).isIn(beforeCredit, beforeCredit + 1);
          assertThat(fixture.service.getBalance("A")).isEqualTo(beforeCredit + 1);
          assertThat(fixture.service.getBalance("B")).isEqualTo(rollback ? 50000 : 50010);
          assertThat(fixture.entries("completion")).hasSize(rollback ? 0 : 2);
          assertThat(fixture.entries("conflict")).hasSize(1);
          assertCleanLocks(fixture);
        } finally {
          control.releaseCompletion.countDown();
          shutdown(executor);
        }
      }
    }
  }

  private static ServiceFixture fixture() {
    return new ServiceFixture(
        TestContexts.memory(PersistenceFaults.class, ServiceFixture.FixedTime.class));
  }

  private static void assertCleanLocks(ServiceFixture fixture) {
    assertThat(fixture.context.getBean("transactionLocks", KeyedLocks.class).slotCount()).isZero();
    assertThat(fixture.context.getBean("accountLocks", KeyedLocks.class).slotCount()).isZero();
  }
}
