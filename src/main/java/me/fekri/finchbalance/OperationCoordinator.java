package me.fekri.finchbalance;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Holds Java locks until TransactionTemplate has finished the actual JPA commit or rollback. */
@Component
class OperationCoordinator {
  private final OperationLocks locks;
  private final BalanceStore store;
  private final StoredOutcomes storedOutcomes;
  private final TransactionTemplate transaction;

  OperationCoordinator(
      OperationLocks locks,
      BalanceStore store,
      StoredOutcomes storedOutcomes,
      PlatformTransactionManager manager) {
    this.locks = locks;
    this.store = store;
    this.storedOutcomes = storedOutcomes;
    if (!(manager instanceof JpaTransactionManager)) {
      throw new IllegalArgumentException("A JPA transaction manager is required");
    }
    transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  void execute(OperationRequest request, Supplier<OperationOutcome> newOperation) {
    rejectAmbientTransaction();
    OperationOutcome outcome =
        locks.withLocks(
            request,
            () ->
                transaction.execute(
                    status -> {
                      var entries = store.findEntries(request.transactionId());
                      return entries.isEmpty()
                          ? newOperation.get()
                          : storedOutcomes.decode(entries);
                    }));
    Objects.requireNonNull(outcome).report();
  }

  long getBalance(String accountId) {
    return readAccount(accountId, Account::balance);
  }

  <T> T readAccount(String accountId, Function<Account, T> read) {
    Identifiers.requireValid(accountId);
    rejectAmbientTransaction();
    return locks.withAccountLock(
        accountId,
        () ->
            transaction.execute(
                status -> {
                  Account account = store.findAccount(accountId);
                  if (account == null) {
                    throw new BalanceException(FailureCode.ACCOUNT_NOT_FOUND);
                  }
                  return read.apply(account);
                }));
  }

  static void rejectAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Balance operations cannot join an ambient transaction");
    }
  }
}
