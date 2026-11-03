package me.fekri.finchbalance;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Balance API backed by final history and lock-coordinated JPA transactions. */
@Service
class JpaBalanceService implements BalanceService {
  private final OperationCoordinator coordinator;
  private final BalanceStore store;
  private final Clock clock;

  JpaBalanceService(OperationCoordinator coordinator, BalanceStore store, Clock clock) {
    this.coordinator = coordinator;
    this.store = store;
    this.clock = clock;
  }

  @Override
  public void credit(String accountId, long amount, String transactionId) {
    var request =
        new OperationRequest(OperationType.CREDIT, accountId, null, amount, transactionId);
    coordinator.execute(request, () -> apply(request));
  }

  @Override
  public void debit(String accountId, long amount, String transactionId) {
    var request = new OperationRequest(OperationType.DEBIT, accountId, null, amount, transactionId);
    coordinator.execute(request, () -> apply(request));
  }

  @Override
  public void transfer(
      String sourceAccountId, String destinationAccountId, long amount, String transactionId) {
    var request =
        new OperationRequest(
            OperationType.TRANSFER, sourceAccountId, destinationAccountId, amount, transactionId);
    coordinator.execute(request, () -> apply(request));
  }

  @Override
  public long getBalance(String accountId) {
    return coordinator.getBalance(accountId);
  }

  private OperationOutcome apply(OperationRequest request) {
    Account account = store.findAccount(request.accountId());
    boolean transfer = request.type() == OperationType.TRANSFER;
    Account destination = transfer ? store.findAccount(request.destinationAccountId()) : null;
    BalanceChange change = BalanceChange.calculate(request, account, destination);
    boolean succeeded = change.failureCode() == null;
    if (succeeded) {
      account.changeBalance(change.balance());
      if (transfer) {
        destination.changeBalance(change.destinationBalance());
      }
    }
    Instant createdAt = clock.instant();
    store.append(
        new TransactionEntry(
            request.transactionId(),
            transfer ? EntryRole.SOURCE : EntryRole.SINGLE,
            request.accountId(),
            request.destinationAccountId(),
            request.amount(),
            request.type(),
            succeeded ? EntryStatus.SUCCEEDED : EntryStatus.FAILED,
            change.failureCode(),
            createdAt));
    if (succeeded && transfer) {
      store.append(
          new TransactionEntry(
              request.transactionId(),
              EntryRole.DESTINATION,
              request.destinationAccountId(),
              request.accountId(),
              request.amount(),
              request.type(),
              EntryStatus.SUCCEEDED,
              null,
              createdAt));
    }
    return new OperationOutcome(change.failureCode());
  }
}
