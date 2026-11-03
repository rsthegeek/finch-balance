package me.fekri.finchbalance;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One immutable final history entry; account identifiers deliberately have no foreign keys. */
@Entity
@Immutable
@Table(name = "transactions")
public class TransactionEntry {
  @EmbeddedId private EntryId id;

  @Column(name = "account_id", length = 255, nullable = false, updatable = false)
  private String accountId;

  @Column(name = "counterparty_account_id", length = 255, updatable = false)
  private String counterpartyAccountId;

  @Column(name = "amount", nullable = false, updatable = false)
  private long amount;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "type", length = 16, nullable = false, updatable = false)
  private OperationType type;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "status", length = 16, nullable = false, updatable = false)
  private EntryStatus status;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "failure_code", length = 64, updatable = false)
  private FailureCode failureCode;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** Constructor for JPA field access. */
  protected TransactionEntry() {}

  /** Creates a final entry whose validity is also guarded by the database constraints. */
  public TransactionEntry(
      String transactionId,
      EntryRole role,
      String accountId,
      String counterpartyAccountId,
      long amount,
      OperationType type,
      EntryStatus status,
      FailureCode failureCode,
      Instant createdAt) {
    id = new EntryId(transactionId, role);
    this.accountId = accountId;
    this.counterpartyAccountId = counterpartyAccountId;
    this.amount = amount;
    this.type = type;
    this.status = status;
    this.failureCode = failureCode;
    this.createdAt = createdAt;
  }

  /** Returns the persistent identity. */
  public EntryId id() {
    return id;
  }

  /** Returns the submitted affected account ID. */
  public String accountId() {
    return accountId;
  }

  /** Returns the other transfer account, or null for a single entry. */
  public String counterpartyAccountId() {
    return counterpartyAccountId;
  }

  /** Returns the successful or attempted amount in minor units. */
  public long amount() {
    return amount;
  }

  /** Returns the recorded operation type. */
  public OperationType type() {
    return type;
  }

  /** Returns the final recorded status. */
  public EntryStatus status() {
    return status;
  }

  /** Returns the stable rejection code, or null on success. */
  public FailureCode failureCode() {
    return failureCode;
  }

  /** Returns the final entry creation instant. */
  public Instant createdAt() {
    return createdAt;
  }
}
