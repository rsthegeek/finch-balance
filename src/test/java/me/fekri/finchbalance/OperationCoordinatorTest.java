package me.fekri.finchbalance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class OperationCoordinatorTest {
  @Test
  void finalBusinessFailureCommitsBeforeReportingAndReplaysBeforeNewRules() {
    try (var context = TestContexts.memory()) {
      var coordinator = context.getBean(OperationCoordinator.class);
      var store = context.getBean(BalanceStore.class);
      var completed = new AtomicBoolean();
      assertThatThrownBy(
              () ->
                  coordinator.execute(
                      request("failure"),
                      () -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                        assertThat(
                                TransactionSynchronizationManager
                                    .getCurrentTransactionIsolationLevel())
                            .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
                        TransactionSynchronizationManager.registerSynchronization(
                            new TransactionSynchronization() {
                              @Override
                              public void afterCompletion(int status) {
                                assertThat(status).isEqualTo(STATUS_COMMITTED);
                                completed.set(true);
                              }
                            });
                        store.append(entry("failure", FailureCode.INVALID_AMOUNT));
                        return OperationOutcome.failed(FailureCode.INVALID_AMOUNT);
                      }))
          .isInstanceOfSatisfying(
              BalanceException.class,
              failure -> {
                assertThat(failure.code()).isEqualTo(FailureCode.INVALID_AMOUNT);
                assertThat(completed).isTrue();
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              });
      assertThatThrownBy(
              () ->
                  coordinator.execute(
                      new OperationRequest(
                          OperationType.TRANSFER, "missing", "also-missing", -1, "failure"),
                      () -> {
                        throw new AssertionError("Duplicate must never execute new rules");
                      }))
          .isInstanceOfSatisfying(
              BalanceException.class,
              failure -> assertThat(failure.code()).isEqualTo(FailureCode.INVALID_AMOUNT));
      coordinator.execute(
          request("seed:opening:A"),
          () -> {
            throw new AssertionError("Successful duplicate must replay");
          });
      TestTransactions.spring(
          context,
          () -> {
            assertThat(store.findEntries("failure")).hasSize(1);
            assertThat(store.findAccount("A").balance()).isEqualTo(100000);
            return null;
          });
    }
  }

  @Test
  void unexpectedFailureAfterFlushRollsBackBalanceAndHistory() {
    try (var context = TestContexts.memory()) {
      var coordinator = context.getBean(OperationCoordinator.class);
      var store = context.getBean(BalanceStore.class);
      assertThatThrownBy(
              () ->
                  coordinator.execute(
                      request("rollback"),
                      () -> {
                        store.findAccount("A").changeBalance(100001);
                        store.append(entry("rollback", null));
                        context.getBean(EntityManager.class).flush();
                        throw new IllegalStateException("controlled infrastructure fault");
                      }))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("controlled infrastructure fault");
      TestTransactions.spring(
          context,
          () -> {
            assertThat(store.findAccount("A").balance()).isEqualTo(100000);
            assertThat(store.findEntries("rollback")).isEmpty();
            return null;
          });
      assertThat(context.getBean("transactionLocks", KeyedLocks.class).slotCount()).isZero();
      assertThat(context.getBean("accountLocks", KeyedLocks.class).slotCount()).isZero();
    }
  }

  @Test
  void ambientTransactionsAndCorruptStoredGroupsCannotTriggerNewExecution() {
    try (var context = TestContexts.memory()) {
      var coordinator = context.getBean(OperationCoordinator.class);
      var store = context.getBean(BalanceStore.class);
      TestTransactions.spring(
          context,
          () -> {
            assertThatThrownBy(
                    () ->
                        coordinator.execute(
                            request("ambient"),
                            () -> {
                              throw new AssertionError("Ambient transaction reached writes");
                            }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ambient");
            store.append(
                new TransactionEntry(
                    "corrupt",
                    EntryRole.SOURCE,
                    "A",
                    "B",
                    1,
                    OperationType.TRANSFER,
                    EntryStatus.SUCCEEDED,
                    null,
                    Instant.EPOCH));
            return null;
          });
      assertThatThrownBy(
              () ->
                  coordinator.execute(
                      request("corrupt"),
                      () -> {
                        throw new AssertionError("Corrupt history must not be repaired");
                      }))
          .isInstanceOf(HistoryIntegrityException.class);
      TestTransactions.spring(
          context,
          () -> {
            assertThat(store.findEntries("ambient")).isEmpty();
            assertThat(store.findEntries("corrupt")).hasSize(1);
            assertThat(store.findAccount("A").balance()).isEqualTo(100000);
            return null;
          });
    }
  }

  private static OperationRequest request(String id) {
    return new OperationRequest(OperationType.CREDIT, "A", null, 1, id);
  }

  private static TransactionEntry entry(String id, FailureCode failure) {
    return new TransactionEntry(
        id,
        EntryRole.SINGLE,
        "A",
        null,
        failure == null ? 1 : 0,
        OperationType.CREDIT,
        failure == null ? EntryStatus.SUCCEEDED : EntryStatus.FAILED,
        failure,
        Instant.EPOCH);
  }
}
