package me.fekri.finchbalance.repository;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import me.fekri.finchbalance.entity.Account;
import me.fekri.finchbalance.entity.TransactionEntry;
import org.springframework.stereotype.Repository;

/** Focused persistence operations used within a caller-owned JPA transaction. */
@Repository
public class BalanceStore {
  private final EntityManager entityManager;

  /** Uses the transaction-bound entity manager supplied by Spring. */
  public BalanceStore(EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  /** Finds a managed account in the current transaction, or null if it does not exist. */
  public Account findAccount(String accountId) {
    return entityManager.find(Account.class, accountId);
  }

  /** Returns every final entry for the original transaction ID, without assuming row order. */
  public List<TransactionEntry> findEntries(String transactionId) {
    return entityManager
        .createQuery(
            "select e from TransactionEntry e where e.id.transactionId = :transactionId",
            TransactionEntry.class)
        .setParameter("transactionId", transactionId)
        .getResultList();
  }

  /** Sums retained successful history exactly, including opening credits. */
  public BigInteger reconstruct(String accountId) {
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

  /** Appends with persist so an existing history ID must fail rather than be overwritten. */
  public void append(TransactionEntry entry) {
    entityManager.persist(entry);
  }
}
