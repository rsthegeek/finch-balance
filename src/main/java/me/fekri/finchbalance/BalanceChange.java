package me.fekri.finchbalance;

/** Validated final balances, calculated without mutating either managed account. */
record BalanceChange(FailureCode failureCode, long balance, long destinationBalance) {
  static BalanceChange calculate(OperationRequest request, Account account, Account destination) {
    if (request.amount() <= 0) {
      return failed(FailureCode.INVALID_AMOUNT);
    }
    boolean transfer = request.type() == OperationType.TRANSFER;
    if (transfer && request.accountId().equals(request.destinationAccountId())) {
      return failed(FailureCode.SAME_ACCOUNT_TRANSFER);
    }
    if (account == null) {
      return failed(FailureCode.ACCOUNT_NOT_FOUND);
    }
    if (transfer && destination == null) {
      return failed(FailureCode.ACCOUNT_NOT_FOUND);
    }
    if (request.type() != OperationType.CREDIT && account.balance() < request.amount()) {
      return failed(FailureCode.INSUFFICIENT_FUNDS);
    }
    try {
      long balance =
          request.type() == OperationType.CREDIT
              ? Math.addExact(account.balance(), request.amount())
              : account.balance() - request.amount();
      long destinationBalance =
          transfer ? Math.addExact(destination.balance(), request.amount()) : 0;
      return new BalanceChange(null, balance, destinationBalance);
    } catch (ArithmeticException overflow) {
      return failed(FailureCode.BALANCE_OVERFLOW);
    }
  }

  private static BalanceChange failed(FailureCode code) {
    return new BalanceChange(code, 0, 0);
  }
}
