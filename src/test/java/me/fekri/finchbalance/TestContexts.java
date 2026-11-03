package me.fekri.finchbalance;

import java.nio.file.Path;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

final class TestContexts {
  private TestContexts() {}

  static ConfigurableApplicationContext memory() {
    return open("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_ON_EXIT=FALSE");
  }

  static ConfigurableApplicationContext file(Path database) {
    return open("jdbc:h2:file:" + database + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0");
  }

  private static ConfigurableApplicationContext open(String url) {
    return new SpringApplicationBuilder(FinchbalanceApplication.class)
        .profiles("test")
        .run("--spring.datasource.url=" + url, "--spring.main.banner-mode=off");
  }
}
