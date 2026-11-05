package me.fekri.finchbalance.dto;

import java.util.List;
import java.util.Objects;
import me.fekri.finchbalance.model.OperationType;
import me.fekri.finchbalance.validation.Identifiers;

/** Immutable submitted envelope; business rules deliberately remain outside this constructor. */
public record OperationRequest(
    OperationType type,
    String accountId,
    String destinationAccountId,
    long amount,
    String transactionId) {
  /** Validates identifier shape while deferring business rules until after outcome lookup. */
  public OperationRequest {
    Objects.requireNonNull(type);
    Identifiers.requireValid(accountId);
    Identifiers.requireValid(transactionId);
    if (type == OperationType.TRANSFER) {
      Identifiers.requireValid(destinationAccountId);
    } else if (destinationAccountId != null) {
      throw new IllegalArgumentException("Only transfers have a destination");
    }
  }

  /** Returns the submitted accounts; locking sorts and deduplicates these identifiers. */
  public List<String> accountIds() {
    return type == OperationType.TRANSFER
        ? List.of(accountId, destinationAccountId)
        : List.of(accountId);
  }
}
