package me.fekri.finchbalance;

import java.math.BigInteger;

/** Exact account snapshot and its reconstructed successful history under the same account lock. */
public record Reconciliation(String accountId, long balance, BigInteger reconstructedBalance) {
  /** Returns whether retained successful history exactly explains the stored balance. */
  public boolean matches() {
    return reconstructedBalance.equals(BigInteger.valueOf(balance));
  }
}
