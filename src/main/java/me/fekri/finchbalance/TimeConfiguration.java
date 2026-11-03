package me.fekri.finchbalance;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies a shared UTC time source for final history entries. */
@Configuration
public class TimeConfiguration {
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
