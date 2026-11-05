package me.fekri.finchbalance.controller;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class BalanceControllerTest {
  private final JdbcTemplate jdbc;
  private final JsonMapper json;
  @LocalServerPort private int port;

  @Autowired
  BalanceControllerTest(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  @BeforeEach
  void restoreIsolatedFixtures() {
    jdbc.update("DELETE FROM transactions WHERE transaction_id NOT LIKE 'seed:%'");
    jdbc.update(
        """
        UPDATE accounts
        SET balance = CASE id WHEN 'A' THEN 100000 WHEN 'B' THEN 50000 ELSE 0 END
        """);
  }

  @Test
  void allOperationsCommitBalancesAndCompleteHistoryBeforeResponding() throws Exception {
    assertThat(post("credits", mutation("C", "1000", "http:credit")).statusCode()).isEqualTo(204);
    assertThat(post("debits", mutation("C", "250", "http:debit")).statusCode()).isEqualTo(204);
    assertThat(
            post(
                    "transfers",
                    """
                    {"sourceAccountId":"A","destinationAccountId":"C",
                     "amount":500,"transactionId":"http:transfer"}
                    """)
                .statusCode())
        .isEqualTo(204);
    var response = get("/api/accounts/C/balance");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(json.readTree(response.body()).get("accountId").asText()).isEqualTo("C");
    assertThat(json.readTree(response.body()).get("balance").asLong()).isEqualTo(1250);
    assertThat(balance("A")).isEqualTo(99500);
    assertThat(balance("B")).isEqualTo(50000);
    assertThat(entries("http:credit")).isOne();
    assertThat(entries("http:debit")).isOne();
    assertThat(
            jdbc.queryForList(
                "SELECT entry_role FROM transactions WHERE transaction_id = ?",
                String.class,
                "http:transfer"))
        .containsExactlyInAnyOrder("SOURCE", "DESTINATION");
    assertThat(
            jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM transactions
                WHERE transaction_id = ? AND amount = 500 AND status = 'SUCCEEDED'
                  AND ((entry_role = 'SOURCE' AND account_id = 'A'
                        AND counterparty_account_id = 'C')
                    OR (entry_role = 'DESTINATION' AND account_id = 'C'
                        AND counterparty_account_id = 'A'))
                """,
                Long.class,
                "http:transfer"))
        .isEqualTo(2);
  }

  @Test
  void successfulReplayIgnoresChangedBusinessPayloadAcrossOperationTypes() throws Exception {
    var first = post("credits", mutation("C", "10", "http:replay"));
    assertThat(first.statusCode()).isEqualTo(204);
    assertThat(first.body()).isEmpty();
    assertThat(post("debits", mutation("unknown", "-1", "http:replay")).statusCode())
        .isEqualTo(204);
    assertThat(
            post(
                    "transfers",
                    """
                    {"sourceAccountId":"A","destinationAccountId":"A",
                     "amount":0,"transactionId":"http:replay"}
                    """)
                .statusCode())
        .isEqualTo(204);
    assertThat(balance("C")).isEqualTo(10);
    assertThat(balance("A")).isEqualTo(100000);
    assertThat(entries("http:replay")).isOne();
  }

  @Test
  void failedReplayKeepsItsOriginalCodeAfterFunding() throws Exception {
    assertError(post("debits", mutation("C", "10", "http:failed")), 409, "INSUFFICIENT_FUNDS");
    assertThat(post("credits", mutation("C", "100", "http:funding")).statusCode()).isEqualTo(204);
    assertError(post("credits", mutation("A", "1", "http:failed")), 409, "INSUFFICIENT_FUNDS");
    assertThat(balance("C")).isEqualTo(100);
    assertThat(balance("A")).isEqualTo(100000);
    assertThat(entries("http:failed")).isOne();
    assertThat(
            jdbc.queryForMap(
                """
                SELECT status, failure_code, account_id, amount
                FROM transactions WHERE transaction_id = ?
                """,
                "http:failed"))
        .containsEntry("STATUS", "FAILED")
        .containsEntry("FAILURE_CODE", "INSUFFICIENT_FUNDS")
        .containsEntry("ACCOUNT_ID", "C")
        .containsEntry("AMOUNT", 10L);
  }

  @Test
  void businessErrorsHaveStableStatusesAndPersistFinalOutcomes() throws Exception {
    assertError(post("credits", mutation("C", "0", "http:invalid")), 400, "INVALID_AMOUNT");
    assertError(
        post("credits", mutation("unknown", "1", "http:unknown")), 404, "ACCOUNT_NOT_FOUND");
    assertError(get("/api/accounts/unknown/balance"), 404, "ACCOUNT_NOT_FOUND");
    assertError(
        post(
            "transfers",
            """
            {"sourceAccountId":"A","destinationAccountId":"A",
             "amount":1,"transactionId":"http:self"}
            """),
        400,
        "SAME_ACCOUNT_TRANSFER");
    assertError(
        post("credits", mutation("A", Long.toString(Long.MAX_VALUE), "http:overflow")),
        409,
        "BALANCE_OVERFLOW");
    for (String id : List.of("http:invalid", "http:unknown", "http:self", "http:overflow")) {
      assertThat(entries(id)).isOne();
    }
    assertThat(balance("A")).isEqualTo(100000);
    assertThat(balance("C")).isZero();
  }

  @Test
  void malformedJsonAndIdentifiersNeverCreateOutcomesOrChangeMoney() throws Exception {
    for (String amount : List.of("null", "1.5", "\"1\"", "true", "9223372036854775808")) {
      assertError(post("credits", mutation("C", amount, "http:malformed")), 400, "INVALID_REQUEST");
    }
    for (String body :
        List.of(
            "{",
            "null",
            "{}",
            "{\"accountId\":\"C\",\"transactionId\":\"http:malformed\"}",
            mutation(" ", "1", "http:malformed"),
            mutation("C", "1", " "),
            mutation("C", "1", "x".repeat(256)))) {
      assertError(post("credits", body), 400, "INVALID_REQUEST");
    }
    assertError(
        post(
            "transfers",
            """
            {"sourceAccountId":"A","destinationAccountId":"C",
             "transactionId":"http:malformed"}
            """),
        400,
        "INVALID_REQUEST");
    assertError(
        post(
            "transfers",
            "{\"sourceAccountId\":\"A\",\"amount\":1,\"transactionId\":\"http:malformed\"}"),
        400,
        "INVALID_REQUEST");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Long.class)).isEqualTo(2);
    assertThat(balance("C")).isZero();
    assertThat(balance("A")).isEqualTo(100000);
  }

  @Test
  void corruptHistoryReturnsGenericServerErrorWithoutReexecution() throws Exception {
    jdbc.update(
        """
        INSERT INTO transactions
          (transaction_id, entry_role, account_id, counterparty_account_id,
           amount, type, status, created_at)
        VALUES (?, 'SOURCE', 'A', 'B', 1, 'TRANSFER', 'SUCCEEDED', CURRENT_TIMESTAMP)
        """,
        "http:corrupt");
    var response = post("credits", mutation("C", "1", "http:corrupt"));
    assertError(response, 500, "INTERNAL_ERROR");
    assertThat(response.body()).doesNotContain("Invalid committed", "SELECT", "stackTrace");
    assertThat(balance("C")).isZero();
    assertThat(entries("http:corrupt")).isOne();
  }

  @Test
  void databaseConsoleIsDisabledWithoutLocalProfile() throws Exception {
    assertThat(get("/h2-console/").statusCode()).isEqualTo(404);
  }

  private long balance(String account) {
    return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, account);
  }

  private long entries(String id) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM transactions WHERE transaction_id = ?", Long.class, id);
  }

  private static String mutation(String account, String amount, String id) {
    return """
        {"accountId":"%s","amount":%s,"transactionId":"%s"}
        """
        .formatted(account, amount, id);
  }

  private HttpResponse<String> post(String operation, String body) throws Exception {
    return send("/api/" + operation, HttpRequest.BodyPublishers.ofString(body));
  }

  private HttpResponse<String> get(String path) throws Exception {
    return send(path, null);
  }

  private HttpResponse<String> send(String path, HttpRequest.BodyPublisher body) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(10));
    if (body != null) {
      request.header("Content-Type", "application/json").POST(body);
    }
    try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
      return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
  }

  private void assertError(HttpResponse<String> response, int status, String code)
      throws Exception {
    assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(status);
    assertThat(response.headers().firstValue("Content-Type").orElseThrow())
        .startsWith("application/json");
    assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo(code);
  }
}
