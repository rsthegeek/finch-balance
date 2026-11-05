package me.fekri.finchbalance.model;

/** Stable expected business rejection codes. */
public enum FailureCode {
  INVALID_AMOUNT,
  SAME_ACCOUNT_TRANSFER,
  ACCOUNT_NOT_FOUND,
  INSUFFICIENT_FUNDS,
  BALANCE_OVERFLOW
}
