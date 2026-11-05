package me.fekri.finchbalance.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;
import me.fekri.finchbalance.FinchbalanceApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

class LocalAccessTest {
  @TempDir Path directory;

  @Test
  void localProfileServesApiAndConsoleQueriesTheSameTemporaryFileDatabase() throws Exception {
    String url =
        "jdbc:h2:file:" + directory.resolve("balances") + ";DB_CLOSE_ON_EXIT=FALSE;WRITE_DELAY=0";

    try (var context =
            new SpringApplicationBuilder(FinchbalanceApplication.class)
                .profiles("test", "local")
                .run("--server.port=0", "--spring.datasource.url=" + url);
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
      int port = ((WebServerApplicationContext) context).getWebServer().getPort();
      String base = "http://127.0.0.1:" + port;
      var credit =
          request(
                  base + "/api/credits",
                  "application/json",
                  "{\"accountId\":\"C\",\"amount\":37,\"transactionId\":\"local:credit\"}")
              .build();
      assertThat(client.send(credit, HttpResponse.BodyHandlers.ofString()).statusCode())
          .isEqualTo(204);
      var balance =
          client.send(
              request(base + "/api/accounts/C/balance", null, null).build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(balance.statusCode()).isEqualTo(200);
      assertThat(balance.body()).contains("\"balance\":37");

      var console =
          client.send(
              request(base + "/h2-console/", null, null).build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(console.statusCode()).isEqualTo(200);
      var session = Pattern.compile("jsessionid=([a-zA-Z0-9]+)").matcher(console.body());
      assertThat(session.find()).isTrue();
      String consoleBase = base + "/h2-console/";
      String sessionQuery = "?jsessionid=" + session.group(1);
      var login =
          client.send(
              request(
                      consoleBase + "login.do" + sessionQuery,
                      "application/x-www-form-urlencoded",
                      "driver=org.h2.Driver&url="
                          + encode(url + ";IFEXISTS=TRUE")
                          + "&user=sa&password=")
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(login.statusCode()).isEqualTo(200);
      var query =
          client.send(
              request(
                      consoleBase + "query.do" + sessionQuery,
                      "application/x-www-form-urlencoded",
                      "sql=" + encode("SELECT balance FROM accounts WHERE id = 'C'"))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(query.statusCode()).isEqualTo(200);
      assertThat(query.body()).contains("<td>37</td>").doesNotContain("Database not found");
      assertThat(context.getEnvironment().getProperty("server.address")).isEqualTo("127.0.0.1");
      assertThat(
              context
                  .getEnvironment()
                  .getProperty("spring.h2.console.settings.web-allow-others", Boolean.class))
          .isFalse();
    }
    assertThat(directory.resolve("balances.mv.db")).exists();
  }

  private static HttpRequest.Builder request(String url, String contentType, String body) {
    var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
    if (body != null) {
      request.header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body));
    }
    return request;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
