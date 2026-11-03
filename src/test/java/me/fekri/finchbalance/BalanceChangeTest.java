package me.fekri.finchbalance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BalanceChangeTest {
  @Test
  void invalidAmountsPrecedeEveryOtherRule() {
    for (OperationType type : OperationType.values()) {
      for (long amount : new long[] {0, -1, Long.MIN_VALUE}) {
        var request = request(type, "A", type == OperationType.TRANSFER ? "A" : null, amount);
        assertThat(BalanceChange.calculate(request, null, null).failureCode())
            .isEqualTo(FailureCode.INVALID_AMOUNT);
      }
    }
  }

  @Test
  void accountAndFundsValidationHaveDeterministicPriority() {
    assertThat(
            BalanceChange.calculate(request(OperationType.TRANSFER, "A", "A", 1), null, null)
                .failureCode())
        .isEqualTo(FailureCode.SAME_ACCOUNT_TRANSFER);
    for (OperationType type : OperationType.values()) {
      assertThat(
              BalanceChange.calculate(
                      request(type, "A", type == OperationType.TRANSFER ? "B" : null, 1),
                      null,
                      null)
                  .failureCode())
          .isEqualTo(FailureCode.ACCOUNT_NOT_FOUND);
    }
    var empty = new Account("A", "owner-a", 0);
    assertThat(
            BalanceChange.calculate(request(OperationType.TRANSFER, "A", "B", 1), empty, null)
                .failureCode())
        .isEqualTo(FailureCode.ACCOUNT_NOT_FOUND);
    assertThat(
            BalanceChange.calculate(
                    request(OperationType.TRANSFER, "A", "B", 1),
                    empty,
                    new Account("B", "owner-b", Long.MAX_VALUE))
                .failureCode())
        .isEqualTo(FailureCode.INSUFFICIENT_FUNDS);
    assertThat(
            BalanceChange.calculate(request(OperationType.DEBIT, "A", null, 1), empty, null)
                .failureCode())
        .isEqualTo(FailureCode.INSUFFICIENT_FUNDS);
  }

  @Test
  void exactBalanceAndMaximumBoundarySucceedWithoutMutatingInputs() {
    var full = new Account("A", "owner-a", Long.MAX_VALUE);
    var empty = new Account("B", "owner-b", 0);
    assertThat(
            BalanceChange.calculate(
                request(OperationType.DEBIT, "A", null, Long.MAX_VALUE), full, null))
        .isEqualTo(new BalanceChange(null, 0, 0));
    assertThat(
            BalanceChange.calculate(
                request(OperationType.CREDIT, "B", null, Long.MAX_VALUE), empty, null))
        .isEqualTo(new BalanceChange(null, Long.MAX_VALUE, 0));
    assertThat(
            BalanceChange.calculate(
                request(OperationType.TRANSFER, "A", "B", Long.MAX_VALUE), full, empty))
        .isEqualTo(new BalanceChange(null, 0, Long.MAX_VALUE));
    assertThat(full.balance()).isEqualTo(Long.MAX_VALUE);
    assertThat(empty.balance()).isZero();
  }

  @Test
  void creditAndDestinationOverflowRejectBeforeEitherAccountChanges() {
    var full = new Account("A", "owner-a", Long.MAX_VALUE);
    var source = new Account("B", "owner-b", 100);
    assertThat(
            BalanceChange.calculate(request(OperationType.CREDIT, "A", null, 1), full, null)
                .failureCode())
        .isEqualTo(FailureCode.BALANCE_OVERFLOW);
    assertThat(
            BalanceChange.calculate(request(OperationType.TRANSFER, "B", "A", 1), source, full)
                .failureCode())
        .isEqualTo(FailureCode.BALANCE_OVERFLOW);
    assertThat(source.balance()).isEqualTo(100);
    assertThat(full.balance()).isEqualTo(Long.MAX_VALUE);
  }

  private static OperationRequest request(
      OperationType type, String source, String destination, long amount) {
    return new OperationRequest(type, source, destination, amount, "id");
  }
}
