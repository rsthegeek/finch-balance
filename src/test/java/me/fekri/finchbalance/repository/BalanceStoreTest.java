package me.fekri.finchbalance.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import me.fekri.finchbalance.entity.TransactionEntry;
import me.fekri.finchbalance.model.EntryRole;
import me.fekri.finchbalance.model.EntryStatus;
import me.fekri.finchbalance.model.OperationType;
import me.fekri.finchbalance.support.TestContexts;
import me.fekri.finchbalance.support.TestTransactions;
import org.junit.jupiter.api.Test;

class BalanceStoreTest {
  @Test
  void committedDirtyTrackingAndOriginalIdLookupUseFreshPersistenceContexts() {
    try (var context = TestContexts.memory()) {
      BalanceStore store = context.getBean(BalanceStore.class);
      TestTransactions.spring(
          context,
          () -> {
            assertThat(store.findAccount("missing")).isNull();
            store.findAccount("A").changeBalance(99995);
            store.findAccount("B").changeBalance(50005);
            store.append(entry("pair", EntryRole.SOURCE, "A", "B", 5));
            store.append(entry("pair", EntryRole.DESTINATION, "B", "A", 5));
            store.append(entry("unrelated", EntryRole.SINGLE, "C", null, 9));
            return null;
          });
      TestTransactions.spring(
          context,
          () -> {
            assertThat(store.findAccount("A").balance()).isEqualTo(99995);
            assertThat(store.findAccount("B").balance()).isEqualTo(50005);
            assertThat(store.findEntries("pair"))
                .extracting(e -> e.id().role())
                .containsExactlyInAnyOrder(EntryRole.SOURCE, EntryRole.DESTINATION);
            assertThat(store.findEntries("unrelated")).hasSize(1);
            assertThat(store.findEntries("absent")).isEmpty();
            return null;
          });
    }
  }

  @Test
  void duplicateAppendRollsBackAndCannotRewriteCommittedHistory() {
    try (var context = TestContexts.memory()) {
      BalanceStore store = context.getBean(BalanceStore.class);
      var original = entry("immutable", EntryRole.SINGLE, "A", null, 7);
      TestTransactions.spring(
          context,
          () -> {
            store.append(original);
            return null;
          });
      assertThatThrownBy(
              () ->
                  TestTransactions.spring(
                      context,
                      () -> {
                        store.findAccount("A").changeBalance(1);
                        store.append(entry("immutable", EntryRole.SINGLE, "B", null, 99));
                        return null;
                      }))
          .isInstanceOf(RuntimeException.class);
      TestTransactions.spring(
          context,
          () -> {
            assertThat(store.findEntries("immutable"))
                .singleElement()
                .usingRecursiveComparison()
                .isEqualTo(original);
            assertThat(store.findAccount("A").balance()).isEqualTo(100000);
            return null;
          });
    }
  }

  private static TransactionEntry entry(
      String id, EntryRole role, String account, String counterparty, long amount) {
    return new TransactionEntry(
        id,
        role,
        account,
        counterparty,
        amount,
        role == EntryRole.SINGLE ? OperationType.CREDIT : OperationType.TRANSFER,
        EntryStatus.SUCCEEDED,
        null,
        Instant.parse("2026-01-01T00:00:00Z"));
  }
}
