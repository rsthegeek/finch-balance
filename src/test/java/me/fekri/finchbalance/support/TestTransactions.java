package me.fekri.finchbalance.support;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits test fixtures and reads results in real, independent transactions. */
public final class TestTransactions {
  private TestTransactions() {}

  /** Runs with the Spring transaction manager and its transaction-bound entity manager. */
  public static <T> T spring(ApplicationContext context, Supplier<T> action) {
    return new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
        .execute(status -> action.get());
  }

  /** Runs with a fresh persistence context, completing commit or rollback before returning. */
  public static <T> T run(ApplicationContext context, Function<EntityManager, T> action) {
    try (EntityManager manager =
        context.getBean(EntityManagerFactory.class).createEntityManager()) {
      var transaction = manager.getTransaction();
      transaction.begin();
      try {
        T result = action.apply(manager);
        transaction.commit();
        return result;
      } catch (RuntimeException | Error failure) {
        if (transaction.isActive()) {
          transaction.rollback();
        }
        throw failure;
      }
    }
  }
}
