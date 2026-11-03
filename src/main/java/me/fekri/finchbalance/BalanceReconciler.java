package me.fekri.finchbalance;

import org.springframework.stereotype.Service;

/** Audits account balances against retained successful history, including opening credits. */
@Service
public class BalanceReconciler {
  private final OperationCoordinator coordinator;
  private final BalanceStore store;

  BalanceReconciler(OperationCoordinator coordinator, BalanceStore store) {
    this.coordinator = coordinator;
    this.store = store;
  }

  /**
   * Reads a stable account/history view under its account lock and reports any discrepancy.
   *
   * @param accountId account whose history should explain its balance
   * @return the stored balance and exact reconstructed value
   * @throws BalanceException if the account does not exist
   */
  public Reconciliation reconcile(String accountId) {
    return coordinator.readAccount(
        accountId,
        account ->
            new Reconciliation(account.id(), account.balance(), store.reconstruct(account.id())));
  }
}
