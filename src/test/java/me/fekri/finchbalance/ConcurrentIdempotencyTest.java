package me.fekri.finchbalance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ConcurrentIdempotencyTest {
  @Test
  void duplicateCallersAllObserveExactlyOneFinalOutcomeAcrossAllOperations() throws Exception {
    try (var fixture = new ServiceFixture()) {
      assertThat(
              ConcurrentServiceCalls.run(
                  24, ignored -> () -> fixture.service.credit("A", 10, "credit-duplicates")))
          .containsOnlyNulls();
      assertThat(
              ConcurrentServiceCalls.run(
                  24, ignored -> () -> fixture.service.debit("A", 10, "debit-duplicates")))
          .containsOnlyNulls();
      assertThat(
              ConcurrentServiceCalls.run(
                  24,
                  ignored -> () -> fixture.service.transfer("A", "B", 10, "transfer-duplicates")))
          .containsOnlyNulls();
      assertThat(
              ConcurrentServiceCalls.run(
                  24, ignored -> () -> fixture.service.transfer("C", "A", 1, "failed-duplicates")))
          .containsOnly(FailureCode.INSUFFICIENT_FUNDS);
      fixture.service.credit("C", 5, "fund-C");
      assertThat(
              ConcurrentServiceCalls.run(
                  24, ignored -> () -> fixture.service.transfer("C", "B", 1, "failed-duplicates")))
          .containsOnly(FailureCode.INSUFFICIENT_FUNDS);
      assertThat(fixture.service.getBalance("A")).isEqualTo(99990);
      assertThat(fixture.service.getBalance("B")).isEqualTo(50010);
      assertThat(fixture.service.getBalance("C")).isEqualTo(5);
      assertThat(fixture.entries("credit-duplicates")).hasSize(1);
      assertThat(fixture.entries("debit-duplicates")).hasSize(1);
      assertThat(fixture.entries("transfer-duplicates")).hasSize(2);
      assertThat(fixture.entries("failed-duplicates"))
          .singleElement()
          .satisfies(
              entry -> {
                assertThat(entry.id().role()).isEqualTo(EntryRole.SOURCE);
                assertThat(entry.counterpartyAccountId()).isEqualTo("A");
                assertThat(entry.status()).isEqualTo(EntryStatus.FAILED);
                assertThat(entry.failureCode()).isEqualTo(FailureCode.INSUFFICIENT_FUNDS);
              });
    }
  }

  @Test
  void creditAndTransferSharingOriginalIdCommitOnlyTheSchedulingWinnersGroup() throws Exception {
    try (var fixture = new ServiceFixture()) {
      assertThat(
              ConcurrentServiceCalls.run(
                  2,
                  index ->
                      index == 0
                          ? () -> fixture.service.credit("A", 11, "cross-type")
                          : () -> fixture.service.transfer("B", "C", 7, "cross-type")))
          .containsOnlyNulls();
      var entries = fixture.entries("cross-type");
      if (entries.getFirst().type() == OperationType.CREDIT) {
        assertThat(entries)
            .hasSize(1)
            .allSatisfy(entry -> assertThat(entry.id().role()).isEqualTo(EntryRole.SINGLE));
        assertThat(fixture.service.getBalance("A")).isEqualTo(100011);
        assertThat(fixture.service.getBalance("B")).isEqualTo(50000);
        assertThat(fixture.service.getBalance("C")).isZero();
      } else {
        assertThat(entries)
            .hasSize(2)
            .allSatisfy(entry -> assertThat(entry.type()).isEqualTo(OperationType.TRANSFER));
        assertThat(entries)
            .extracting(entry -> entry.id().role())
            .containsExactlyInAnyOrder(EntryRole.SOURCE, EntryRole.DESTINATION);
        assertThat(fixture.service.getBalance("A")).isEqualTo(100000);
        assertThat(fixture.service.getBalance("B")).isEqualTo(49993);
        assertThat(fixture.service.getBalance("C")).isEqualTo(7);
      }
      assertThat(fixture.context.getBean("transactionLocks", KeyedLocks.class).slotCount())
          .isZero();
      assertThat(fixture.context.getBean("accountLocks", KeyedLocks.class).slotCount()).isZero();
    }
  }
}
