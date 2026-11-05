package me.fekri.finchbalance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import me.fekri.finchbalance.entity.TransactionEntry;
import me.fekri.finchbalance.exception.BalanceException;
import me.fekri.finchbalance.model.FailureCode;
import me.fekri.finchbalance.repository.BalanceStore;
import me.fekri.finchbalance.support.TestContexts;
import me.fekri.finchbalance.support.TestTransactions;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

final class ServiceFixture implements AutoCloseable {
  static final Instant NOW = Instant.parse("2026-11-01T10:00:00.123456Z");
  final ConfigurableApplicationContext context;
  final BalanceService service;
  final BalanceStore store;

  ServiceFixture() {
    this(TestContexts.memory(FixedTime.class));
  }

  ServiceFixture(ConfigurableApplicationContext context) {
    this.context = context;
    service = context.getBean(BalanceService.class);
    store = context.getBean(BalanceStore.class);
  }

  List<TransactionEntry> entries(String id) {
    return TestTransactions.spring(context, () -> store.findEntries(id));
  }

  void setBalance(String account, long balance) {
    TestTransactions.spring(
        context,
        () -> {
          store.findAccount(account).changeBalance(balance);
          return null;
        });
  }

  static void rejects(FailureCode code, Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOfSatisfying(
            BalanceException.class, failure -> assertThat(failure.code()).isEqualTo(code));
  }

  @Override
  public void close() {
    context.close();
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class FixedTime {
    @Bean
    @Primary
    Clock fixedClock() {
      return Clock.fixed(NOW, ZoneOffset.UTC);
    }
  }
}
