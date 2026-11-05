package me.fekri.finchbalance.dto;

/** JSON credit/debit envelope; business validation remains behind stored-outcome lookup. */
public record BalanceMutation(String accountId, Long amount, String transactionId) {
  /** Requires an explicit amount instead of interpreting a missing JSON field as zero. */
  public BalanceMutation {
    if (amount == null) {
      throw new IllegalArgumentException("amount is required");
    }
  }
}
