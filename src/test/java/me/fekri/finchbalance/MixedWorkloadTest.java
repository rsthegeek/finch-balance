package me.fekri.finchbalance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MixedWorkloadTest {
  @Test
  void uniqueAndDuplicateCreditsAndDebitsOnOneAccountHaveExactNetEffect() throws Exception {
    try (var fixture = new ServiceFixture()) {
      assertThat(
              ConcurrentServiceCalls.run(
                  120,
                  index ->
                      () -> {
                        int logical = index % 60;
                        if (logical % 2 == 0) {
                          fixture.service.credit("A", 5, "single:" + logical);
                        } else {
                          fixture.service.debit("A", 3, "single:" + logical);
                        }
                      }))
          .containsOnlyNulls();
      assertThat(fixture.service.getBalance("A")).isEqualTo(100060);
      assertThat(fixture.service.getBalance("B")).isEqualTo(50000);
      assertThat(fixture.service.getBalance("C")).isZero();
      assertCardinality(fixture, "single:", 60, 60, 0, 60);
    }
  }

  @Test
  void thousandMixedCallsHaveFiveHundredFinalOutcomesAndIndependentlyDerivedTotals()
      throws Exception {
    try (var fixture = new ServiceFixture()) {
      var outcomes =
          ConcurrentServiceCalls.run(
              1000,
              index ->
                  () -> {
                    int logical = index % 500;
                    String id = "mixed:" + logical;
                    switch (logical % 5) {
                      case 0 -> fixture.service.credit("A", 3, id);
                      case 1 -> fixture.service.debit("A", 2, id);
                      case 2 -> fixture.service.transfer("A", "B", 1, id);
                      case 3 -> fixture.service.transfer("B", "C", 1, id);
                      case 4 -> fixture.service.credit("C", 0, id);
                      default -> throw new AssertionError("Unreachable workload case");
                    }
                  });
      assertThat(outcomes).hasSize(1000);
      assertThat(outcomes).filteredOn(Objects::isNull).hasSize(800);
      assertThat(outcomes)
          .filteredOn(Objects::nonNull)
          .hasSize(200)
          .containsOnly(FailureCode.INVALID_AMOUNT);
      // 100 of each unique case: A gains 300-200-100; B gains 100-100; C gains 100.
      assertThat(fixture.service.getBalance("A")).isEqualTo(100000);
      assertThat(fixture.service.getBalance("B")).isEqualTo(50000);
      assertThat(fixture.service.getBalance("C")).isEqualTo(100);
      assertCardinality(fixture, "mixed:", 700, 500, 100, 400);
      assertThat(fixture.context.getBean("transactionLocks", KeyedLocks.class).slotCount())
          .isZero();
      assertThat(fixture.context.getBean("accountLocks", KeyedLocks.class).slotCount()).isZero();
    }
  }

  private static void assertCardinality(
      ServiceFixture fixture, String prefix, int rows, int logical, int failed, int successful) {
    var jdbc = new JdbcTemplate(fixture.context.getBean(DataSource.class));
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE transaction_id LIKE ?",
                Integer.class,
                prefix + "%"))
        .isEqualTo(rows);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(DISTINCT transaction_id) FROM transactions"
                    + " WHERE transaction_id LIKE ?",
                Integer.class,
                prefix + "%"))
        .isEqualTo(logical);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions"
                    + " WHERE transaction_id LIKE ? AND status = 'FAILED'",
                Integer.class,
                prefix + "%"))
        .isEqualTo(failed);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(DISTINCT transaction_id) FROM transactions"
                    + " WHERE transaction_id LIKE ? AND status = 'SUCCEEDED'",
                Integer.class,
                prefix + "%"))
        .isEqualTo(successful);
  }
}
