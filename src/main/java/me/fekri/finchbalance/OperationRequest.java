package me.fekri.finchbalance;

import java.util.List;
import java.util.Objects;

/** Immutable submitted envelope; business rules deliberately remain outside this constructor. */
record OperationRequest(
    OperationType type,
    String accountId,
    String destinationAccountId,
    long amount,
    String transactionId) {
  OperationRequest {
    Objects.requireNonNull(type);
    Identifiers.requireValid(accountId);
    Identifiers.requireValid(transactionId);
    if (type == OperationType.TRANSFER) {
      Identifiers.requireValid(destinationAccountId);
    } else if (destinationAccountId != null) {
      throw new IllegalArgumentException("Only transfers have a destination");
    }
  }

  List<String> accountIds() {
    return type == OperationType.TRANSFER
        ? List.of(accountId, destinationAccountId)
        : List.of(accountId);
  }
}
