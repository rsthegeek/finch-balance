package me.fekri.finchbalance.concurrency;

import static me.fekri.finchbalance.support.ConcurrentTestSupport.await;
import static me.fekri.finchbalance.support.ConcurrentTestSupport.awaitWaiting;
import static me.fekri.finchbalance.support.ConcurrentTestSupport.shutdown;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.fekri.finchbalance.dto.OperationRequest;
import me.fekri.finchbalance.model.OperationType;
import org.junit.jupiter.api.Test;

class OperationLocksTest {
  @Test
  void originalIdPrecedesSortedAccountsForBothTransferDirectionsAndSelfTransfer() {
    var events = new ArrayList<String>();
    var transactions = new RecordingLocks("transaction:", events);
    var accounts = new RecordingLocks("account:", events);
    var locks = new OperationLocks(transactions, accounts);
    for (var request : List.of(transfer("B", "A"), transfer("A", "B"))) {
      locks.withLocks(request, () -> null);
      assertThat(events).containsExactly("transaction:id", "account:A", "account:B");
      events.clear();
    }
    locks.withLocks(transfer("A", "A"), () -> null);
    assertThat(events).containsExactly("transaction:id", "account:A");
    assertThat(transactions.slotCount()).isZero();
    assertThat(accounts.slotCount()).isZero();
  }

  @Test
  void callbackAndPartialAcquisitionFailuresReleaseEveryLease() {
    var transactions = new KeyedLocks();
    var accounts = new KeyedLocks();
    var locks = new OperationLocks(transactions, accounts);
    assertThatThrownBy(
            () ->
                locks.withLocks(
                    transfer("A", "B"),
                    () -> {
                      throw new IllegalStateException("callback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(transactions.slotCount()).isZero();
    assertThat(accounts.slotCount()).isZero();
    var failing =
        new KeyedLocks() {
          @Override
          public Lease acquire(String key) {
            if (key.equals("B")) {
              throw new IllegalStateException("acquisition");
            }
            return super.acquire(key);
          }
        };
    var partial = new OperationLocks(transactions, failing);
    assertThatThrownBy(() -> partial.withLocks(transfer("A", "B"), () -> null))
        .isInstanceOf(IllegalStateException.class);
    assertThat(transactions.slotCount()).isZero();
    assertThat(failing.slotCount()).isZero();
  }

  @Test
  void sameOriginalIdSerializesDifferentTypesWhileUnrelatedWorkCompletes() throws Exception {
    var transactions = new KeyedLocks();
    var accounts = new KeyedLocks();
    var locks = new OperationLocks(transactions, accounts);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var attempting = new CountDownLatch(1);
    var secondThread = new AtomicReference<Thread>();
    var executor = Executors.newFixedThreadPool(3);
    try {
      final var first =
          executor.submit(
              () ->
                  locks.withLocks(
                      new OperationRequest(OperationType.CREDIT, "A", null, 1, "id"),
                      () -> {
                        entered.countDown();
                        await(release);
                        return null;
                      }));
      await(entered);
      final var second =
          executor.submit(
              () -> {
                secondThread.set(Thread.currentThread());
                attempting.countDown();
                return locks.withLocks(transfer("B", "C"), () -> null);
              });
      await(attempting);
      awaitWaiting(secondThread.get());
      executor
          .submit(
              () ->
                  locks.withLocks(
                      new OperationRequest(OperationType.DEBIT, "D", null, 1, "other"), () -> 42))
          .get(10, TimeUnit.SECONDS);
      assertThat(second).isNotDone();
      release.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
      assertThat(transactions.slotCount()).isZero();
      assertThat(accounts.slotCount()).isZero();
    } finally {
      release.countDown();
      shutdown(executor);
    }
  }

  private static OperationRequest transfer(String source, String destination) {
    return new OperationRequest(OperationType.TRANSFER, source, destination, 1, "id");
  }

  private static class RecordingLocks extends KeyedLocks {
    private final String prefix;
    private final List<String> events;

    RecordingLocks(String prefix, List<String> events) {
      this.prefix = prefix;
      this.events = events;
    }

    @Override
    public Lease acquire(String key) {
      events.add(prefix + key);
      return super.acquire(key);
    }
  }
}
