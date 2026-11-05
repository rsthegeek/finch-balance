package me.fekri.finchbalance.concurrency;

import static me.fekri.finchbalance.support.ConcurrentTestSupport.await;
import static me.fekri.finchbalance.support.ConcurrentTestSupport.awaitWaiting;
import static me.fekri.finchbalance.support.ConcurrentTestSupport.shutdown;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class KeyedLocksTest {
  @Test
  void sameKeyNeverOverlapsAcrossRepeatedAcquireReleaseInterleavings() throws Exception {
    var locks = new KeyedLocks();
    var executor = Executors.newFixedThreadPool(8);
    var start = new CountDownLatch(1);
    var active = new AtomicInteger();
    var completed = new AtomicInteger();
    var futures = new ArrayList<Future<?>>();
    try {
      for (int worker = 0; worker < 8; worker++) {
        futures.add(
            executor.submit(
                () -> {
                  await(start);
                  for (int i = 0; i < 200; i++) {
                    try (var ignored = locks.acquire("same")) {
                      assertThat(active.incrementAndGet()).isEqualTo(1);
                      completed.incrementAndGet();
                      assertThat(active.decrementAndGet()).isZero();
                    }
                  }
                }));
      }
      start.countDown();
      for (var future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
      assertThat(completed).hasValue(1600);
      assertThat(locks.slotCount()).isZero();
    } finally {
      start.countDown();
      shutdown(executor);
    }
  }

  @Test
  void queuedWaiterKeepsItsSlotAndNewcomersShareThatSameLock() throws Exception {
    var locks = new KeyedLocks();
    var executor = Executors.newFixedThreadPool(2);
    var owner = locks.acquire("key");
    var attempting = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var waiterThread = new AtomicReference<Thread>();
    var newcomerThread = new AtomicReference<Thread>();
    var newcomerAttempting = new CountDownLatch(1);
    var active = new AtomicInteger();
    boolean ownerClosed = false;
    try {
      final Future<?> waiter =
          executor.submit(
              () -> {
                waiterThread.set(Thread.currentThread());
                attempting.countDown();
                try (var ignored = locks.acquire("key")) {
                  assertThat(active.incrementAndGet()).isEqualTo(1);
                  entered.countDown();
                  await(release);
                  active.decrementAndGet();
                }
              });
      await(attempting);
      awaitWaiting(waiterThread.get());
      owner.close();
      ownerClosed = true;
      await(entered);
      assertThat(locks.slotCount()).isEqualTo(1);
      final Future<?> newcomer =
          executor.submit(
              () -> {
                newcomerThread.set(Thread.currentThread());
                newcomerAttempting.countDown();
                try (var ignored = locks.acquire("key")) {
                  assertThat(active.incrementAndGet()).isEqualTo(1);
                  active.decrementAndGet();
                }
              });
      await(newcomerAttempting);
      awaitWaiting(newcomerThread.get());
      release.countDown();
      waiter.get(10, TimeUnit.SECONDS);
      newcomer.get(10, TimeUnit.SECONDS);
      assertThat(locks.slotCount()).isZero();
    } finally {
      if (!ownerClosed) {
        owner.close();
      }
      release.countDown();
      shutdown(executor);
    }
  }

  @Test
  void distinctKeysProgressAndOwnershipAndExceptionalCleanupAreEnforced() throws Exception {
    var locks = new KeyedLocks();
    var executor = Executors.newSingleThreadExecutor();
    var lease = locks.acquire("A");
    try {
      executor
          .submit(
              () -> {
                try (var ignored = locks.acquire("B")) {
                  assertThatThrownBy(lease::close).isInstanceOf(IllegalStateException.class);
                }
              })
          .get(10, TimeUnit.SECONDS);
      assertThat(locks.slotCount()).isEqualTo(1);
    } finally {
      lease.close();
      shutdown(executor);
    }
    assertThatThrownBy(lease::close).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> {
              try (var ignored = locks.acquire("exception")) {
                throw new IllegalArgumentException("controlled");
              }
            })
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(locks.slotCount()).isZero();
  }
}
