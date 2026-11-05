package me.fekri.finchbalance.service;

import static me.fekri.finchbalance.service.ServiceFixture.rejects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import me.fekri.finchbalance.entity.TransactionEntry;
import me.fekri.finchbalance.model.EntryRole;
import me.fekri.finchbalance.model.EntryStatus;
import me.fekri.finchbalance.model.FailureCode;
import me.fekri.finchbalance.model.OperationType;
import me.fekri.finchbalance.support.TestTransactions;
import org.junit.jupiter.api.Test;

class CreditServiceTest {
  @Test
  void creditCommitsOneCorrectEntryAndSequentialDuplicatesReturnNormally() {
    try (var fixture = new ServiceFixture()) {
      fixture.service.credit("A", 25, "credit");
      fixture.service.credit("A", 999, "credit");
      fixture.service.credit("missing", -1, "credit");
      assertThat(fixture.service.getBalance("A")).isEqualTo(100025);
      assertThat(fixture.entries("credit"))
          .singleElement()
          .usingRecursiveComparison()
          .isEqualTo(
              new TransactionEntry(
                  "credit",
                  EntryRole.SINGLE,
                  "A",
                  null,
                  25,
                  OperationType.CREDIT,
                  EntryStatus.SUCCEEDED,
                  null,
                  ServiceFixture.NOW));
      assertThat(fixture.service.getBalance("B")).isEqualTo(50000);
      assertThat(fixture.service.getBalance("C")).isZero();
    }
  }

  @Test
  void invalidAmountUnknownAccountAndOverflowCommitFinalFailures() {
    try (var fixture = new ServiceFixture()) {
      for (long amount : new long[] {0, -1, Long.MIN_VALUE}) {
        String id = "amount:" + amount;
        rejects(FailureCode.INVALID_AMOUNT, () -> fixture.service.credit("A", amount, id));
        assertFailure(fixture, id, "A", amount, FailureCode.INVALID_AMOUNT);
      }
      rejects(FailureCode.ACCOUNT_NOT_FOUND, () -> fixture.service.credit("missing", 5, "unknown"));
      assertFailure(fixture, "unknown", "missing", 5, FailureCode.ACCOUNT_NOT_FOUND);
      assertThat(fixture.service.getBalance("A")).isEqualTo(100000);
      fixture.setBalance("A", Long.MAX_VALUE);
      rejects(FailureCode.BALANCE_OVERFLOW, () -> fixture.service.credit("A", 1, "overflow"));
      assertFailure(fixture, "overflow", "A", 1, FailureCode.BALANCE_OVERFLOW);
      assertThat(fixture.service.getBalance("A")).isEqualTo(Long.MAX_VALUE);
      rejects(FailureCode.INVALID_AMOUNT, () -> fixture.service.credit("C", 1, "amount:0"));
      assertThat(fixture.service.getBalance("C")).isZero();
    }
  }

  @Test
  void balanceReadsAndMalformedEnvelopesCannotWriteHistoryOrJoinAmbientTransactions() {
    try (var fixture = new ServiceFixture()) {
      rejects(FailureCode.ACCOUNT_NOT_FOUND, () -> fixture.service.getBalance("missing"));
      assertThatThrownBy(() -> fixture.service.getBalance(" "))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> fixture.service.credit(null, 1, "malformed"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(fixture.entries("malformed")).isEmpty();
      TestTransactions.spring(
          fixture.context,
          () -> {
            assertThatThrownBy(() -> fixture.service.getBalance("A"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambient");
            return null;
          });
    }
  }

  private static void assertFailure(
      ServiceFixture fixture, String id, String account, long amount, FailureCode code) {
    assertThat(fixture.entries(id))
        .singleElement()
        .usingRecursiveComparison()
        .isEqualTo(
            new TransactionEntry(
                id,
                EntryRole.SINGLE,
                account,
                null,
                amount,
                OperationType.CREDIT,
                EntryStatus.FAILED,
                code,
                ServiceFixture.NOW));
  }
}
