package me.fekri.finchbalance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class SeedMigrationTest {
  @TempDir Path directory;

  @Test
  void seedsExactlyMatchOpeningHistory() {
    try (var context = TestContexts.memory()) {
      var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
      assertThat(jdbc.queryForList("SELECT id, user_id, balance FROM accounts ORDER BY id"))
          .containsExactly(
              Map.of("ID", "A", "USER_ID", "user-a", "BALANCE", 100000L),
              Map.of("ID", "B", "USER_ID", "user-b", "BALANCE", 50000L),
              Map.of("ID", "C", "USER_ID", "user-c", "BALANCE", 0L));
      assertThat(
              jdbc.queryForList(
                  "SELECT transaction_id, entry_role, account_id, amount, type, status"
                      + " FROM transactions ORDER BY account_id"))
          .containsExactly(
              Map.of(
                  "TRANSACTION_ID",
                  "seed:opening:A",
                  "ENTRY_ROLE",
                  "SINGLE",
                  "ACCOUNT_ID",
                  "A",
                  "AMOUNT",
                  100000L,
                  "TYPE",
                  "CREDIT",
                  "STATUS",
                  "SUCCEEDED"),
              Map.of(
                  "TRANSACTION_ID",
                  "seed:opening:B",
                  "ENTRY_ROLE",
                  "SINGLE",
                  "ACCOUNT_ID",
                  "B",
                  "AMOUNT",
                  50000L,
                  "TYPE",
                  "CREDIT",
                  "STATUS",
                  "SUCCEEDED"));
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM transactions"
                      + " WHERE counterparty_account_id IS NOT NULL OR failure_code IS NOT NULL",
                  Integer.class))
          .isZero();
    }
  }

  @Test
  void reopeningFileDatabaseDoesNotRepeatSeedsOrResetCommittedBalances() {
    Path database = directory.resolve("seed-restart");
    try (var context = TestContexts.file(database)) {
      var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
      // JdbcTemplate runs this fixture update in auto-commit mode before pool shutdown.
      jdbc.update("UPDATE accounts SET balance = ? WHERE id = ?", 12345, "A");
    }
    try (var context = TestContexts.file(database)) {
      var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
      assertThat(jdbc.queryForObject("SELECT balance FROM accounts WHERE id = 'A'", Long.class))
          .isEqualTo(12345L);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class)).isEqualTo(3);
      assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class))
          .isEqualTo(2);
      assertThat(
              jdbc.queryForObject(
                  "SELECT COUNT(*) FROM \"flyway_schema_history\""
                      + " WHERE \"success\" = TRUE AND \"version\" IN ('1', '2')",
                  Integer.class))
          .isEqualTo(2);
    }
  }
}
