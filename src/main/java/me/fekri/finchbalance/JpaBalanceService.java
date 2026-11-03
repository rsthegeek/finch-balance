package me.fekri.finchbalance;

import java.time.Clock;
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
    coordinator.execute(request, () -> applyCredit(request));
  }

  @Override
  public void debit(String accountId, long amount, String transactionId) {
    throw new UnsupportedOperationException("Debit is not implemented yet (T14)");
  }

  @Override
  public void transfer(
      String sourceAccountId, String destinationAccountId, long amount, String transactionId) {
    throw new UnsupportedOperationException("Transfer is not implemented yet (T15)");
  }

  @Override
  public long getBalance(String accountId) {
    return coordinator.getBalance(accountId);
  }

  private OperationOutcome applyCredit(OperationRequest request) {
    Account account = store.findAccount(request.accountId());
    BalanceChange change = BalanceChange.calculate(request, account, null);
    if (change.failureCode() == null) {
      account.changeBalance(change.balance());
    }
    store.append(
        new TransactionEntry(
            request.transactionId(),
            EntryRole.SINGLE,
            request.accountId(),
            null,
            request.amount(),
            request.type(),
            change.failureCode() == null ? EntryStatus.SUCCEEDED : EntryStatus.FAILED,
            change.failureCode(),
            clock.instant()));
    return new OperationOutcome(change.failureCode());
  }
}
