package me.fekri.finchbalance;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.springframework.stereotype.Repository;

/** Focused persistence operations used within a caller-owned JPA transaction. */
@Repository
class BalanceStore {
  private final EntityManager entityManager;

  BalanceStore(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  Account findAccount(String accountId) {
    return entityManager.find(Account.class, accountId);
  }

  List<TransactionEntry> findEntries(String transactionId) {
    return entityManager
        .createQuery(
            "select e from TransactionEntry e where e.id.transactionId = :transactionId",
            TransactionEntry.class)
        .setParameter("transactionId", transactionId)
        .getResultList();
  }

  BigInteger reconstruct(String accountId) {
    BigDecimal total =
        (BigDecimal)
            entityManager
                .createNativeQuery(
                    """
        SELECT COALESCE(SUM(CASE
            WHEN entry_role = 'DESTINATION' OR (entry_role = 'SINGLE' AND type = 'CREDIT')
                THEN CAST(amount AS DECIMAL(100, 0))
            ELSE -CAST(amount AS DECIMAL(100, 0))
        END), CAST(0 AS DECIMAL(100, 0)))
        FROM transactions
        WHERE account_id = :accountId AND status = 'SUCCEEDED'
        """,
                    BigDecimal.class)
                .setParameter("accountId", accountId)
                .getSingleResult();
    return total.toBigIntegerExact();
  }

  // Persist deliberately bypasses save/merge: an existing history ID must fail, never overwrite.
  void append(TransactionEntry entry) {
    entityManager.persist(entry);
  }
}
