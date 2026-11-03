package me.fekri.finchbalance;

import java.util.ArrayList;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Acquires original-ID first, then distinct account IDs in natural String order. */
@Component
class OperationLocks {
  private final KeyedLocks transactionLocks;
  private final KeyedLocks accountLocks;

  OperationLocks(
      @Qualifier("transactionLocks") KeyedLocks transactionLocks,
      @Qualifier("accountLocks") KeyedLocks accountLocks) {
    this.transactionLocks = transactionLocks;
    this.accountLocks = accountLocks;
  }

  <T> T withLocks(OperationRequest request, Supplier<T> action) {
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

  <T> T withAccountLock(String accountId, Supplier<T> action) {
    try (var ignored = accountLocks.acquire(accountId)) {
      return action.get();
    }
  }
}
