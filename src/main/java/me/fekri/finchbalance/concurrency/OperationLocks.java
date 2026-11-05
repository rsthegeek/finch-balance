package me.fekri.finchbalance.concurrency;

import java.util.ArrayList;
import java.util.function.Supplier;
import me.fekri.finchbalance.dto.OperationRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Acquires original-ID first, then distinct account IDs in natural String order. */
@Component
public class OperationLocks {
  private final KeyedLocks transactionLocks;
  private final KeyedLocks accountLocks;

  /** Uses separate shared registries for original transaction IDs and account IDs. */
  public OperationLocks(
      @Qualifier("transactionLocks") KeyedLocks transactionLocks,
      @Qualifier("accountLocks") KeyedLocks accountLocks) {
    this.transactionLocks = transactionLocks;
    this.accountLocks = accountLocks;
  }

  /** Runs an operation under its original-ID lock and sorted, distinct account locks. */
  public <T> T withLocks(OperationRequest request, Supplier<T> action) {
    try (var ignored = transactionLocks.acquire(request.transactionId())) {
      var accounts = new ArrayList<KeyedLocks.Lease>();
      try {
        for (String accountId : request.accountIds().stream().distinct().sorted().toList()) {
          accounts.add(accountLocks.acquire(accountId));
        }
        return action.get();
      } finally {
        for (int i = accounts.size() - 1; i >= 0; i--) {
          accounts.get(i).close();
        }
      }
    }
  }

  /** Runs an account read under only its account lock, preserving the lock hierarchy. */
  public <T> T withAccountLock(String accountId, Supplier<T> action) {
    try (var ignored = accountLocks.acquire(accountId)) {
      return action.get();
    }
  }
}
