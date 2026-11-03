package me.fekri.finchbalance;

/** Concurrent single-currency balance operations with durable, original-ID final outcomes. */
public interface BalanceService {
  /** Credits minor units, or replays the first committed outcome for the transaction ID. */
  void credit(String accountId, long amount, String transactionId);

  /** Debits minor units, or replays the first committed outcome for the transaction ID. */
  void debit(String accountId, long amount, String transactionId);

  /** Transfers minor units atomically, or replays the original transaction's final outcome. */
  void transfer(
      String sourceAccountId, String destinationAccountId, long amount, String transactionId);

  /** Returns the committed balance under the account lock; unknown accounts are rejected. */
  long getBalance(String accountId);
}
