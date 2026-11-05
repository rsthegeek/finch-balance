package me.fekri.finchbalance.dto;

/** JSON transfer envelope; amount and account-state rules are evaluated by the service. */
public record TransferMutation(
    String sourceAccountId, String destinationAccountId, Long amount, String transactionId) {
  /** Requires an explicit amount while preserving valid negative/zero business attempts. */
  public TransferMutation {
    if (amount == null) {
      throw new IllegalArgumentException("amount is required");
    }
  }
}
