package me.fekri.finchbalance.dto;

import java.util.Objects;
import me.fekri.finchbalance.exception.BalanceException;
import me.fekri.finchbalance.model.FailureCode;

/** Final business result returned across the database commit boundary. */
public record OperationOutcome(FailureCode failureCode) {
  /** Creates a successful final result. */
  public static OperationOutcome success() {
    return new OperationOutcome(null);
  }

  /** Creates a rejected final result with a stable failure code. */
  public static OperationOutcome failed(FailureCode code) {
    return new OperationOutcome(Objects.requireNonNull(code));
  }

  /** Reports a business rejection after the transaction containing its outcome has committed. */
  public void report() {
    if (failureCode != null) {
      throw new BalanceException(failureCode);
    }
  }
}
