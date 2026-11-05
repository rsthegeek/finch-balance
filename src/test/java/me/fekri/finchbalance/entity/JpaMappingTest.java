package me.fekri.finchbalance.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import me.fekri.finchbalance.model.EntryRole;
import me.fekri.finchbalance.model.EntryStatus;
import me.fekri.finchbalance.model.FailureCode;
import me.fekri.finchbalance.model.OperationType;
import me.fekri.finchbalance.support.TestContexts;
import me.fekri.finchbalance.support.TestTransactions;
import org.junit.jupiter.api.Test;

class JpaMappingTest {
  @Test
  void allValidEntryFormsSurviveFlushClearAndFreshContextReload() {
    Instant instant = Instant.parse("2026-10-30T12:13:14.123456Z");
    var entries =
        List.of(
            entry("credit", EntryRole.SINGLE, "A", null, 5, OperationType.CREDIT, null, instant),
            entry("debit", EntryRole.SINGLE, "B", null, 5, OperationType.DEBIT, null, instant),
            entry("pair", EntryRole.SOURCE, "A", "B", 5, OperationType.TRANSFER, null, instant),
            entry(
                "pair", EntryRole.DESTINATION, "B", "A", 5, OperationType.TRANSFER, null, instant),
            entry(
                "bad-credit",
                EntryRole.SINGLE,
                "missing",
                null,
                Long.MIN_VALUE,
                OperationType.CREDIT,
                FailureCode.INVALID_AMOUNT,
                instant),
            entry(
                "bad-debit",
                EntryRole.SINGLE,
                "missing",
                null,
                5,
                OperationType.DEBIT,
                FailureCode.ACCOUNT_NOT_FOUND,
                instant),
            entry(
                "bad-transfer",
                EntryRole.SOURCE,
                "missing",
                "other",
                5,
                OperationType.TRANSFER,
                FailureCode.ACCOUNT_NOT_FOUND,
                instant));
    try (var context = TestContexts.memory()) {
      TestTransactions.run(
          context,
          manager -> {
            manager.persist(new Account("mapped", "mapped-owner", Long.MAX_VALUE));
            entries.forEach(manager::persist);
            manager.flush();
            manager.clear();
            for (var expected : entries) {
              assertEntry(manager.find(TransactionEntry.class, expected.id()), expected);
            }
            return null;
          });
      TestTransactions.run(
          context,
          manager -> {
            Account account = manager.find(Account.class, "mapped");
            assertThat(account.userId()).isEqualTo("mapped-owner");
            assertThat(account.balance()).isEqualTo(Long.MAX_VALUE);
            for (var expected : entries) {
              assertEntry(manager.find(TransactionEntry.class, expected.id()), expected);
            }
            return null;
          });
    }
  }

  @Test
  void compositeIdentityDistinguishesRolesAndOriginalIdsInHashCollections() {
    var source = new EntryId("original", EntryRole.SOURCE);
    assertThat(source).isEqualTo(new EntryId("original", EntryRole.SOURCE));
    var keys =
        new HashSet<>(
            List.of(
                source,
                new EntryId("original", EntryRole.SOURCE),
                new EntryId("original", EntryRole.DESTINATION),
                new EntryId("other", EntryRole.SOURCE)));
    assertThat(keys).hasSize(3).contains(new EntryId("original", EntryRole.SOURCE));
    assertThat(source).isNotEqualTo(null).isNotEqualTo("original");
  }

  private static TransactionEntry entry(
      String id,
      EntryRole role,
      String account,
      String counterparty,
      long amount,
      OperationType type,
      FailureCode failure,
      Instant instant) {
    return new TransactionEntry(
        id,
        role,
        account,
        counterparty,
        amount,
        type,
        failure == null ? EntryStatus.SUCCEEDED : EntryStatus.FAILED,
        failure,
        instant);
  }

  private static void assertEntry(TransactionEntry actual, TransactionEntry expected) {
    assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
  }
}
