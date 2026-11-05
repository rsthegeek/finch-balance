package me.fekri.finchbalance.support;

import java.nio.file.Path;
import java.util.UUID;
import me.fekri.finchbalance.FinchbalanceApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Opens isolated Spring contexts using real migrations and persistence. */
public final class TestContexts {
  private TestContexts() {}

  /** Opens a fresh, independently named in-memory test database. */
  public static ConfigurableApplicationContext memory(Class<?>... sources) {
    return open("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_ON_EXIT=FALSE", sources);
  }

  /** Opens a file database at a caller-supplied temporary test path. */
  public static ConfigurableApplicationContext file(Path database, Class<?>... sources) {
    return open("jdbc:h2:file:" + database + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0", sources);
  }

  private static ConfigurableApplicationContext open(String url, Class<?>... sources) {
    return new SpringApplicationBuilder(FinchbalanceApplication.class)
        .sources(sources)
        .profiles("test")
        .run("--spring.datasource.url=" + url, "--spring.main.banner-mode=off");
  }
}
