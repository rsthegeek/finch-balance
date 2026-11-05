package me.fekri.finchbalance.config;

import me.fekri.finchbalance.concurrency.KeyedLocks;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Separates original-ID exclusion from ordered account locking within one application context. */
@Configuration
class LockConfiguration {
  @Bean
  KeyedLocks transactionLocks() {
    return new KeyedLocks();
  }

  @Bean
  KeyedLocks accountLocks() {
    return new KeyedLocks();
  }
}
