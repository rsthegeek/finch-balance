package me.fekri.finchbalance;

import java.nio.file.Path;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

final class TestContexts {
  private TestContexts() {}

  static ConfigurableApplicationContext memory(Class<?>... sources) {
    return open("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_ON_EXIT=FALSE", sources);
  }

  static ConfigurableApplicationContext file(Path database, Class<?>... sources) {
    return open("jdbc:h2:file:" + database + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0", sources);
  }

  private static ConfigurableApplicationContext open(String url, Class<?>... sources) {
    return new SpringApplicationBuilder(FinchbalanceApplication.class)
        .sources(sources)
        .profiles("test")
        .run("--spring.datasource.url=" + url, "--spring.main.banner-mode=off");
  }
}
