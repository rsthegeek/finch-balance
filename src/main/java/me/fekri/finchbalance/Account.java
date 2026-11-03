package me.fekri.finchbalance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Managed balance and explicit ownership of one account. */
@Entity
@Table(name = "accounts")
public class Account {
  @Id
  @Column(name = "id", length = 255, nullable = false, updatable = false)
  private String id;

  @Column(name = "user_id", length = 255, nullable = false, unique = true, updatable = false)
  private String userId;

  @Column(name = "balance", nullable = false)
  private long balance;

  /** Constructor for JPA field access. */
  protected Account() {}

  Account(String id, String userId, long balance) {
    this.id = id;
    this.userId = userId;
    changeBalance(balance);
  }

  /** Returns the persistent identity. */
  public String id() {
    return id;
  }

  /** Returns the explicit account owner. */
  public String userId() {
    return userId;
  }

  /** Returns the current balance in minor units. */
  public long balance() {
    return balance;
  }

  void changeBalance(long balance) {
    if (balance < 0) {
      throw new IllegalArgumentException("Balance must be nonnegative");
    }
    this.balance = balance;
  }
}
