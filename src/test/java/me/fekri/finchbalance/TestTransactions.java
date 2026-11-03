package me.fekri.finchbalance;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.function.Function;
import org.springframework.context.ApplicationContext;

final class TestTransactions {
  private TestTransactions() {}

  static <T> T run(ApplicationContext context, Function<EntityManager, T> action) {
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
