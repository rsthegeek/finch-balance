package me.fekri.finchbalance.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import me.fekri.finchbalance.FinchbalanceApplication;
import me.fekri.finchbalance.support.TestContexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseConfigurationTest {
  @TempDir Path directory;

  @Test
  void normalConfigurationOpensFileDatabaseInAnIsolatedWorkingDirectory() throws Exception {
    Path output = directory.resolve("probe.log");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("java.class.path"),
                NormalConfigurationProbe.class.getName())
            .directory(directory.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue())
          .withFailMessage(java.nio.file.Files.readString(output))
          .isZero();
      assertThat(directory.resolve("data/finchbalance.mv.db")).exists();
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  void independentlyNamedMemoryDatabasesDoNotShareData() throws Exception {
    try (var first = TestContexts.memory();
        var second = TestContexts.memory()) {
      DataSource firstSource = first.getBean(DataSource.class);
      DataSource secondSource = second.getBean(DataSource.class);
      try (Connection firstConnection = firstSource.getConnection();
          Connection secondConnection = secondSource.getConnection()) {
        assertThat(firstConnection.getMetaData().getURL()).startsWith("jdbc:h2:mem:");
        assertThat(secondConnection.getMetaData().getURL())
            .startsWith("jdbc:h2:mem:")
            .isNotEqualTo(firstConnection.getMetaData().getURL());
      }
      var firstJdbc = new JdbcTemplate(firstSource);
      var secondJdbc = new JdbcTemplate(secondSource);
      firstJdbc.execute("CREATE TABLE isolation_probe (id INT PRIMARY KEY)");
      firstJdbc.update("INSERT INTO isolation_probe VALUES (1)");
      assertThat(
              secondJdbc.queryForObject(
                  "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?",
                  Integer.class,
                  "ISOLATION_PROBE"))
          .isZero();
      assertThat(first.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
    }
  }

  /** Runs normal application properties in a separate JVM rooted in a temporary directory. */
  public static class NormalConfigurationProbe {
    /** Opens and verifies the actual configured datasource. */
    public static void main(String[] args) throws Exception {
      try (ConfigurableApplicationContext context =
          new SpringApplicationBuilder(FinchbalanceApplication.class)
              .web(WebApplicationType.NONE)
              .run()) {
        try (Connection connection = context.getBean(DataSource.class).getConnection()) {
          assertThat(connection.getMetaData().getURL())
              .startsWith("jdbc:h2:file:./data/finchbalance");
        }
        assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
            .isEqualTo("validate");
        assertThat(context.getEnvironment().getProperty("spring.sql.init.mode")).isEqualTo("never");
        var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        assertThat(
                jdbc.queryForList(
                    "SELECT setting_value FROM information_schema.settings WHERE setting_name = ?",
                    String.class,
                    "WRITE_DELAY"))
            .isNotEmpty()
            .containsOnly("0");
      }
    }
  }
}
