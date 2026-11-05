package me.fekri.finchbalance.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded coordination and cleanup for concurrent callers in regression tests. */
public final class ConcurrentTestSupport {
  private ConcurrentTestSupport() {}

  /** Waits for an explicit scheduling gate with a bounded timeout. */
  public static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).as("latch completed").isTrue();
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  /** Waits until a caller is parked so a lock handoff can be tested. */
  public static void awaitWaiting(Thread thread) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
  }

  /** Stops workers and verifies their termination even after a failed assertion. */
  public static void shutdown(ExecutorService executor) throws InterruptedException {
    executor.shutdownNow();
    assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
  }
}
