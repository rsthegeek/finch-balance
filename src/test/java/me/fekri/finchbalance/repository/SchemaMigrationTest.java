package me.fekri.finchbalance.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import javax.sql.DataSource;
import me.fekri.finchbalance.support.TestContexts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SchemaMigrationTest {
  private ConfigurableApplicationContext context;
  private JdbcTemplate jdbc;

  @BeforeAll
  void open() {
    context = TestContexts.memory();
    jdbc = new JdbcTemplate(context.getBean(DataSource.class));
  }

  @AfterAll
  void close() {
    context.close();
  }

  @Test
  void migrationCreatesExactBusinessColumnsAndNoAccountForeignKeys() {
    assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?"
                    + " ORDER BY ordinal_position",
                String.class,
                "ACCOUNTS"))
        .containsExactly("ID", "USER_ID", "BALANCE");
    assertThat(
            jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?"
                    + " ORDER BY ordinal_position",
                String.class,
                "TRANSACTIONS"))
        .containsExactly(
            "TRANSACTION_ID",
            "ENTRY_ROLE",
            "ACCOUNT_ID",
            "COUNTERPARTY_ACCOUNT_ID",
            "AMOUNT",
            "TYPE",
            "STATUS",
            "FAILURE_CODE",
            "CREATED_AT");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.table_constraints"
                    + " WHERE table_name IN ('ACCOUNTS', 'TRANSACTIONS')"
                    + " AND constraint_type = 'FOREIGN KEY'",
                Integer.class))
        .isZero();
  }

  @Test
  void accountConstraintsRejectInvalidBalancesDuplicateIdsAndDuplicateOwners() {
    jdbc.update("INSERT INTO accounts VALUES (?, ?, ?)", "case", "owner", 0);
    jdbc.update("INSERT INTO accounts VALUES (?, ?, ?)", "CASE", "OWNER", Long.MAX_VALUE);
    assertThatThrownBy(() -> jdbc.update("INSERT INTO accounts VALUES ('negative', 'n', -1)"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("INSERT INTO accounts VALUES ('case', 'other', 1)"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("INSERT INTO accounts VALUES ('other', 'owner', 1)"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void validFailuresRetainInvalidAmountsSelfTransfersAndUnknownAccounts() {
    insert("failed-zero", "SINGLE", "missing", null, 0, "CREDIT", "FAILED", "INVALID_AMOUNT");
    insert(
        "failed-min",
        "SINGLE",
        "missing",
        null,
        Long.MIN_VALUE,
        "DEBIT",
        "FAILED",
        "INVALID_AMOUNT");
    insert(
        "failed-self",
        "SOURCE",
        "missing",
        "missing",
        1,
        "TRANSFER",
        "FAILED",
        "SAME_ACCOUNT_TRANSFER");
    insert(
        "failed-unknown",
        "SOURCE",
        "missing",
        "also-missing",
        1,
        "TRANSFER",
        "FAILED",
        "ACCOUNT_NOT_FOUND");
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE status = 'FAILED'", Integer.class))
        .isEqualTo(4);
  }

  @Test
  void entryConstraintsRejectInvalidFormsAndDuplicateCompositeKeys() {
    insert("pair", "SOURCE", "x", "y", 1, "TRANSFER", "SUCCEEDED", null);
    insert("pair", "DESTINATION", "y", "x", 1, "TRANSFER", "SUCCEEDED", null);
    assertThatThrownBy(() -> insert("pair", "SOURCE", "x", "y", 2, "TRANSFER", "SUCCEEDED", null))
        .isInstanceOf(DataIntegrityViolationException.class);
    reject("WRONG", "x", null, 1, "CREDIT", "SUCCEEDED", null);
    reject("SINGLE", "x", null, 1, "WRONG", "SUCCEEDED", null);
    reject("SINGLE", "x", null, 1, "CREDIT", "WRONG", null);
    reject("SINGLE", "x", null, 0, "CREDIT", "SUCCEEDED", null);
    reject("SINGLE", "x", null, -1, "DEBIT", "SUCCEEDED", null);
    reject("SINGLE", "x", null, 1, "CREDIT", "SUCCEEDED", "INVALID_AMOUNT");
    reject("SINGLE", "x", null, 1, "CREDIT", "FAILED", null);
    reject("SINGLE", "x", null, 1, "CREDIT", "FAILED", "");
    reject("SINGLE", "x", null, 1, "CREDIT", "FAILED", "   ");
    reject("SINGLE", "x", null, 1, "CREDIT", "FAILED", "UNKNOWN");
    reject("SINGLE", "x", "y", 1, "CREDIT", "SUCCEEDED", null);
    reject("SOURCE", "x", null, 1, "TRANSFER", "SUCCEEDED", null);
    reject("SOURCE", "x", "y", 1, "DEBIT", "SUCCEEDED", null);
    reject("SINGLE", "x", "y", 1, "TRANSFER", "SUCCEEDED", null);
    reject("DESTINATION", "y", "x", 1, "TRANSFER", "FAILED", "INSUFFICIENT_FUNDS");
    reject("SOURCE", "x", "x", 1, "TRANSFER", "SUCCEEDED", null);
    reject("DESTINATION", "x", "x", 1, "TRANSFER", "SUCCEEDED", null);
    reject(null, "x", null, 1, "CREDIT", "SUCCEEDED", null);
    reject("SINGLE", null, null, 1, "CREDIT", "SUCCEEDED", null);
  }

  private void reject(
      String role,
      String account,
      String counterparty,
      long amount,
      String type,
      String status,
      String failure) {
    assertThatThrownBy(
            () ->
                insert(
                    UUID.randomUUID().toString(),
                    role,
                    account,
                    counterparty,
                    amount,
                    type,
                    status,
                    failure))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  private void insert(
      String id,
      String role,
      String account,
      String counterparty,
      long amount,
      String type,
      String status,
      String failure) {
    jdbc.update(
        "INSERT INTO transactions VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        id,
        role,
        account,
        counterparty,
        amount,
        type,
        status,
        failure,
        OffsetDateTime.parse("2026-01-01T00:00:00Z"));
  }
}
