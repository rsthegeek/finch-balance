package me.fekri.finchbalance;

import java.util.Objects;

/** Final business result returned across the database commit boundary. */
record OperationOutcome(FailureCode failureCode) {
  static OperationOutcome success() {
    return new OperationOutcome(null);
  }

  static OperationOutcome failed(FailureCode code) {
    return new OperationOutcome(Objects.requireNonNull(code));
  }

  void report() {
    if (failureCode != null) {
      throw new BalanceException(failureCode);
    }
  }
}
