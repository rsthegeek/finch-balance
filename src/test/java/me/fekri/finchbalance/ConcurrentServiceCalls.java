package me.fekri.finchbalance;

import static me.fekri.finchbalance.ConcurrentTestSupport.await;
import static me.fekri.finchbalance.ConcurrentTestSupport.shutdown;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

final class ConcurrentServiceCalls {
  private ConcurrentServiceCalls() {}

  static List<FailureCode> run(int count, IntFunction<Runnable> operation) throws Exception {
    int threads = Math.min(8, count);
    var executor = Executors.newFixedThreadPool(threads);
    var ready = new CountDownLatch(threads);
    var start = new CountDownLatch(1);
    var futures = new ArrayList<Future<FailureCode>>();
    try {
      for (int i = 0; i < count; i++) {
        final Runnable action = operation.apply(i);
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  await(start);
                  try {
                    action.run();
                    return null;
                  } catch (BalanceException failure) {
                    return failure.code();
                  }
                }));
      }
      await(ready);
      start.countDown();
      final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      var outcomes = new ArrayList<FailureCode>();
      for (var future : futures) {
        outcomes.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
      }
      return outcomes;
    } finally {
      start.countDown();
      shutdown(executor);
    }
  }
}
