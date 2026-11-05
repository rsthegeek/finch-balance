package me.fekri.finchbalance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.io.Serializable;
import java.util.Objects;
import me.fekri.finchbalance.model.EntryRole;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Original caller ID and entry role together identify an append-only history row. */
@Embeddable
public class EntryId implements Serializable {
  private static final long serialVersionUID = 1L;

  @Column(name = "transaction_id", length = 255, nullable = false, updatable = false)
  private String transactionId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.VARCHAR)
  @Column(name = "entry_role", length = 16, nullable = false, updatable = false)
  private EntryRole role;

  /** Constructor for JPA field access. */
  protected EntryId() {}

  /** Creates the identity of one role under an original transaction ID. */
  public EntryId(String transactionId, EntryRole role) {
    this.transactionId = Objects.requireNonNull(transactionId);
    this.role = Objects.requireNonNull(role);
  }

  /** Returns the unchanged original caller ID. */
  public String transactionId() {
    return transactionId;
  }

  /** Returns the position within the original transaction. */
  public EntryRole role() {
    return role;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof EntryId that
        && transactionId.equals(that.transactionId)
        && role == that.role;
  }

  @Override
  public int hashCode() {
    return Objects.hash(transactionId, role);
  }
}
