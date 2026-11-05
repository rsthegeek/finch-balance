package me.fekri.finchbalance.service;

import static me.fekri.finchbalance.service.ServiceFixture.rejects;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import me.fekri.finchbalance.dto.Reconciliation;
import me.fekri.finchbalance.model.FailureCode;
import org.junit.jupiter.api.Test;

class BalanceReconcilerTest {
  @Test
  void openingHistoryAndMixedCommittedEffectsReconcileAndFailedEntriesAreExcluded()
      throws Exception {
    try (var fixture = new ServiceFixture()) {
      var reconciler = fixture.context.getBean(BalanceReconciler.class);
      assertReconciled(reconciler, "A", 100000);
      assertReconciled(reconciler, "B", 50000);
      assertReconciled(reconciler, "C", 0);
      assertThat(
              ConcurrentServiceCalls.run(
                  90,
                  index ->
                      () -> {
                        switch (index % 3) {
                          case 0 -> fixture.service.transfer("A", "B", 1, "reconcile:" + index);
                          case 1 -> fixture.service.credit("C", 2, "reconcile:" + index);
                          case 2 -> fixture.service.debit("B", 1, "reconcile:" + index);
                          default -> throw new AssertionError("Unreachable workload case");
                        }
                      }))
          .containsOnlyNulls();
      fixture.service.transfer("B", "A", 5, "reverse");
      rejects(
          FailureCode.INVALID_AMOUNT, () -> fixture.service.credit("A", Long.MIN_VALUE, "failed"));
      rejects(
          FailureCode.INSUFFICIENT_FUNDS,
          () -> fixture.service.transfer("C", "A", 1000, "failed-transfer"));
      assertReconciled(reconciler, "A", 99975);
      assertReconciled(reconciler, "B", 49995);
      assertReconciled(reconciler, "C", 60);
      rejects(FailureCode.ACCOUNT_NOT_FOUND, () -> reconciler.reconcile("missing"));
      fixture.setBalance("A", 99976);
      Reconciliation inconsistent = reconciler.reconcile("A");
      assertThat(inconsistent.matches()).isFalse();
      assertThat(inconsistent.balance()).isEqualTo(99976);
      assertThat(inconsistent.reconstructedBalance()).isEqualTo(BigInteger.valueOf(99975));
    }
  }

  @Test
  void lifetimeCreditsAndDebitsCanExceedLongMaximumWhileIndividualBalancesRemainValid() {
    try (var fixture = new ServiceFixture()) {
      fixture.service.credit("C", Long.MAX_VALUE, "wide-credit:1");
      fixture.service.debit("C", Long.MAX_VALUE, "wide-debit:1");
      fixture.service.credit("C", Long.MAX_VALUE, "wide-credit:2");
      fixture.service.debit("C", Long.MAX_VALUE, "wide-debit:2");
      fixture.service.credit("C", 7, "remainder");
      BigInteger credited =
          fixture.entries("wide-credit:1").stream()
              .map(entry -> BigInteger.valueOf(entry.amount()))
              .reduce(BigInteger.ZERO, BigInteger::add)
              .add(BigInteger.valueOf(fixture.entries("wide-credit:2").getFirst().amount()));
      assertThat(credited).isGreaterThan(BigInteger.valueOf(Long.MAX_VALUE));
      assertReconciled(fixture.context.getBean(BalanceReconciler.class), "C", 7);
    }
  }

  private static void assertReconciled(BalanceReconciler reconciler, String account, long balance) {
    Reconciliation result = reconciler.reconcile(account);
    assertThat(result.accountId()).isEqualTo(account);
    assertThat(result.balance()).isEqualTo(balance);
    assertThat(result.reconstructedBalance()).isEqualTo(BigInteger.valueOf(balance));
    assertThat(result.matches()).isTrue();
  }
}
