package me.fekri.finchbalance.service;

import static me.fekri.finchbalance.support.ConcurrentTestSupport.await;

import jakarta.persistence.EntityManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import me.fekri.finchbalance.entity.Account;
import me.fekri.finchbalance.entity.TransactionEntry;
import me.fekri.finchbalance.repository.BalanceStore;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@TestConfiguration(proxyBeanMethods = false)
class PersistenceFaults {
  @Bean
  Control faultControl() {
    return new Control();
  }

  @Bean
  @Primary
  BalanceStore faultStore(EntityManager manager, Control control) {
    return new FaultStore(manager, control);
  }

  enum Mode {
    NONE,
    FIRST_ACCOUNT,
    FIRST_ENTRY,
    OBSERVE_COMMIT,
    OBSERVE_ROLLBACK
  }

  static final class Control {
    final AtomicReference<Mode> next = new AtomicReference<>(Mode.NONE);
    final AtomicLong balanceAfterFlush = new AtomicLong(-1);
    final AtomicLong entriesAfterFlush = new AtomicLong(-1);
    final AtomicInteger completionStatus = new AtomicInteger(-1);
    final CountDownLatch completionReached = new CountDownLatch(1);
    final CountDownLatch releaseCompletion = new CountDownLatch(1);
  }

  static class ControlledFailure extends RuntimeException {
    ControlledFailure() {
      super("controlled persistence fault");
    }
  }

  private static class FaultStore extends BalanceStore {
    private final EntityManager manager;
    private final Control control;

    FaultStore(EntityManager manager, Control control) {
      super(manager);
      this.manager = manager;
      this.control = control;
    }

    @Override
    public Account findAccount(String id) {
      Account managed = super.findAccount(id);
      if (managed != null && id.equals("A") && control.next.get() == Mode.FIRST_ACCOUNT) {
        return new Account() {
          @Override
          public long balance() {
            return managed.balance();
          }

          @Override
          public void changeBalance(long balance) {
            managed.changeBalance(balance);
            manager.flush();
            control.balanceAfterFlush.set(
                ((Number)
                        manager
                            .createNativeQuery(
                                "SELECT balance FROM accounts WHERE id = 'A'", Long.class)
                            .getSingleResult())
                    .longValue());
            control.next.set(Mode.NONE);
            throw new ControlledFailure();
          }
        };
      }
      return managed;
    }

    @Override
    public void append(TransactionEntry entry) {
      super.append(entry);
      Mode mode = control.next.get();
      if (mode == Mode.FIRST_ENTRY && control.next.compareAndSet(mode, Mode.NONE)) {
        manager.flush();
        control.entriesAfterFlush.set(
            ((Number)
                    manager
                        .createNativeQuery(
                            "SELECT COUNT(*) FROM transactions WHERE transaction_id = :id",
                            Long.class)
                        .setParameter("id", entry.id().transactionId())
                        .getSingleResult())
                .longValue());
        throw new ControlledFailure();
      }
      if ((mode == Mode.OBSERVE_COMMIT || mode == Mode.OBSERVE_ROLLBACK)
          && control.next.compareAndSet(mode, Mode.NONE)) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void beforeCommit(boolean readOnly) {
                if (mode == Mode.OBSERVE_ROLLBACK) {
                  manager.flush();
                  throw new ControlledFailure();
                }
              }

              @Override
              public void afterCompletion(int status) {
                control.completionStatus.set(status);
                control.completionReached.countDown();
                await(control.releaseCompletion);
              }
            });
      }
    }
  }
}
