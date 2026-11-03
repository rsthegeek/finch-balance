package me.fekri.finchbalance;

import jakarta.persistence.EntityManager;
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

  // Persist deliberately bypasses save/merge: an existing history ID must fail, never overwrite.
  void append(TransactionEntry entry) {
    entityManager.persist(entry);
  }
}
