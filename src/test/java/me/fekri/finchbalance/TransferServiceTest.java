package me.fekri.finchbalance;

import static me.fekri.finchbalance.ServiceFixture.rejects;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TransferServiceTest {
  @Test
  void transferCommitsBothBalancesAndExactlyOneReciprocalPairWithSharedInstant() {
    try (var fixture = new ServiceFixture()) {
      fixture.service.transfer("A", "B", 100, "transfer");
      fixture.service.transfer("A", "B", 100, "transfer");
      fixture.service.transfer("C", "missing", -1, "transfer");
      assertThat(fixture.service.getBalance("A")).isEqualTo(99900);
      assertThat(fixture.service.getBalance("B")).isEqualTo(50100);
      assertThat(fixture.entries("transfer"))
          .usingRecursiveFieldByFieldElementComparator()
          .containsExactlyInAnyOrder(
              new TransactionEntry(
                  "transfer",
                  EntryRole.SOURCE,
                  "A",
                  "B",
                  100,
                  OperationType.TRANSFER,
                  EntryStatus.SUCCEEDED,
                  null,
                  ServiceFixture.NOW),
              new TransactionEntry(
                  "transfer",
                  EntryRole.DESTINATION,
                  "B",
                  "A",
                  100,
                  OperationType.TRANSFER,
                  EntryStatus.SUCCEEDED,
                  null,
                  ServiceFixture.NOW));
    }
  }

  @Test
  void everyBusinessRejectionLeavesBothBalancesUnchangedAndOnlyRecordsFailedSource() {
    try (var fixture = new ServiceFixture()) {
      var cases =
          List.of(
              new Rejection("missing", "B", 1, FailureCode.ACCOUNT_NOT_FOUND),
              new Rejection("A", "missing", 1, FailureCode.ACCOUNT_NOT_FOUND),
              new Rejection("A", "B", 100001, FailureCode.INSUFFICIENT_FUNDS),
              new Rejection("A", "A", 1, FailureCode.SAME_ACCOUNT_TRANSFER),
              new Rejection("A", "B", 0, FailureCode.INVALID_AMOUNT),
              new Rejection("A", "B", -1, FailureCode.INVALID_AMOUNT),
              new Rejection("A", "B", Long.MIN_VALUE, FailureCode.INVALID_AMOUNT));
      for (int i = 0; i < cases.size(); i++) {
        verifyRejection(fixture, cases.get(i), "rejection:" + i);
      }
      fixture.setBalance("B", Long.MAX_VALUE);
      verifyRejection(
          fixture, new Rejection("A", "B", 1, FailureCode.BALANCE_OVERFLOW), "overflow");
    }
  }

  @Test
  void maximumAmountAndExactSourceBalanceRemainWithinTheLongRange() {
    try (var fixture = new ServiceFixture()) {
      fixture.setBalance("A", Long.MAX_VALUE);
      fixture.service.transfer("A", "C", Long.MAX_VALUE, "maximum");
      assertThat(fixture.service.getBalance("A")).isZero();
      assertThat(fixture.service.getBalance("C")).isEqualTo(Long.MAX_VALUE);
      assertThat(fixture.entries("maximum"))
          .hasSize(2)
          .allSatisfy(entry -> assertThat(entry.amount()).isEqualTo(Long.MAX_VALUE));
    }
  }

  private static void verifyRejection(ServiceFixture fixture, Rejection rejection, String id) {
    long beforeA = fixture.service.getBalance("A");
    long beforeB = fixture.service.getBalance("B");
    for (int duplicate = 0; duplicate < 2; duplicate++) {
      rejects(
          rejection.code(),
          () ->
              fixture.service.transfer(
                  rejection.source(), rejection.destination(), rejection.amount(), id));
    }
    assertThat(fixture.service.getBalance("A")).isEqualTo(beforeA);
    assertThat(fixture.service.getBalance("B")).isEqualTo(beforeB);
    assertThat(fixture.entries(id))
        .singleElement()
        .usingRecursiveComparison()
        .isEqualTo(
            new TransactionEntry(
                id,
                EntryRole.SOURCE,
                rejection.source(),
                rejection.destination(),
                rejection.amount(),
                OperationType.TRANSFER,
                EntryStatus.FAILED,
                rejection.code(),
                ServiceFixture.NOW));
  }

  private record Rejection(String source, String destination, long amount, FailureCode code) {}
}
