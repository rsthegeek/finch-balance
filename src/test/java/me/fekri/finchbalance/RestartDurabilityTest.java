package me.fekri.finchbalance;

import static me.fekri.finchbalance.ServiceFixture.rejects;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

class RestartDurabilityTest {
  @TempDir Path directory;

  @Test
  void allFinalOutcomesReplayAfterFullRestartAndBeyondSeventyTwoHours() {
    Path database = directory.resolve("durability");
    var before = prepareAndClose(database);
    assertThat(before.pool().isClosed()).isTrue();
    try (var fixture = new ServiceFixture(TestContexts.file(database, LaterTime.class))) {
      assertThat(fixture.context.getBean("transactionLocks", KeyedLocks.class))
          .isNotSameAs(before.locks());
      assertThat(fixture.context.getBean(Clock.class).instant())
          .isAfter(ServiceFixture.NOW.plus(Duration.ofHours(72)));
      fixture.service.credit("missing", -1, "credit");
      fixture.service.debit("C", Long.MAX_VALUE, "debit");
      fixture.service.transfer("missing", "missing", -1, "transfer");
      rejects(
          FailureCode.INSUFFICIENT_FUNDS,
          () -> fixture.service.transfer("C", "A", 1, "failed-transfer"));
      rejects(FailureCode.INSUFFICIENT_FUNDS, () -> fixture.service.debit("C", 1, "failed-debit"));
      assertThat(fixture.service.getBalance("A")).isEqualTo(99970);
      assertThat(fixture.service.getBalance("B")).isEqualTo(50020);
      assertThat(fixture.service.getBalance("C")).isEqualTo(2100);
      assertThat(history(fixture))
          .usingRecursiveFieldByFieldElementComparator()
          .containsExactlyElementsOf(before.history());
      assertThat(fixture.entries("rolled-back")).isEmpty();
      fixture.service.credit("C", 7, "rolled-back");
      assertThat(fixture.service.getBalance("C")).isEqualTo(2107);
      assertThat(fixture.entries("rolled-back")).hasSize(1);
    }
  }

  private static BeforeRestart prepareAndClose(Path database) {
    try (var fixture =
        new ServiceFixture(
            TestContexts.file(database, ServiceFixture.FixedTime.class, PersistenceFaults.class))) {
      fixture.service.credit("C", 100, "credit");
      fixture.service.debit("A", 10, "debit");
      fixture.service.transfer("A", "B", 20, "transfer");
      rejects(
          FailureCode.INSUFFICIENT_FUNDS,
          () -> fixture.service.transfer("C", "A", 1000, "failed-transfer"));
      rejects(
          FailureCode.INSUFFICIENT_FUNDS, () -> fixture.service.debit("C", 1000, "failed-debit"));
      fixture.service.credit("C", 2000, "fund-after-failures");
      fixture
          .context
          .getBean(PersistenceFaults.Control.class)
          .next
          .set(PersistenceFaults.Mode.FIRST_ENTRY);
      assertThatThrownBy(() -> fixture.service.transfer("A", "B", 10, "rolled-back"))
          .isInstanceOf(PersistenceFaults.ControlledFailure.class);
      assertThat(fixture.entries("rolled-back")).isEmpty();
      return new BeforeRestart(
          fixture.context.getBean(HikariDataSource.class),
          fixture.context.getBean("transactionLocks", KeyedLocks.class),
          history(fixture));
    }
  }

  private static List<TransactionEntry> history(ServiceFixture fixture) {
    return TestTransactions.run(
        fixture.context,
        manager ->
            manager
                .createQuery(
                    "select e from TransactionEntry e order by e.id.transactionId, e.id.role",
                    TransactionEntry.class)
                .getResultList());
  }

  private record BeforeRestart(
      HikariDataSource pool, KeyedLocks locks, List<TransactionEntry> history) {}

  @TestConfiguration(proxyBeanMethods = false)
  static class LaterTime {
    @Bean
    @Primary
    Clock laterClock() {
      return Clock.fixed(ServiceFixture.NOW.plus(Duration.ofHours(96)), ZoneOffset.UTC);
    }
  }
}
