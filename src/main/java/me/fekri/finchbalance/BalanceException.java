package me.fekri.finchbalance;

import java.util.Objects;

/** Expected business rejection, reported after its final outcome commits. */
public class BalanceException extends RuntimeException {
  private final FailureCode code;

  /** Creates a business rejection with a stable code. */
  public BalanceException(FailureCode code) {
    super(Objects.requireNonNull(code).name());
    this.code = code;
  }

  /** Returns the stable recorded rejection code. */
  public FailureCode code() {
    return code;
  }
}
