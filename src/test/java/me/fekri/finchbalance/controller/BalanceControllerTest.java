package me.fekri.finchbalance.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class BalanceControllerTest {
  private final JdbcTemplate jdbc;
  private final MockMvcTester mvc;

  @Autowired
  BalanceControllerTest(JdbcTemplate jdbc, MockMvcTester mvc) {
    this.jdbc = jdbc;
    this.mvc = mvc;
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
  void allOperationsCommitBalancesAndCompleteHistoryBeforeResponding() {
    assertThat(post("credits", mutation("C", "1000", "http:credit"))).hasStatus(204);
    assertThat(post("debits", mutation("C", "250", "http:debit"))).hasStatus(204);
    assertThat(
            post(
                "transfers",
                """
                    {"sourceAccountId":"A","destinationAccountId":"C",
                     "amount":500,"transactionId":"http:transfer"}
                    """))
        .hasStatus(204);
    var response = get("/api/accounts/C/balance");
    assertThat(response)
        .hasStatusOk()
        .bodyJson()
        .isStrictlyEqualTo("{\"accountId\":\"C\",\"balance\":1250}");
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
  void successfulReplayIgnoresChangedBusinessPayloadAcrossOperationTypes() {
    var first = post("credits", mutation("C", "10", "http:replay"));
    assertThat(first).hasStatus(204);
    assertThat(first).bodyText().isEmpty();
    assertThat(post("debits", mutation("unknown", "-1", "http:replay"))).hasStatus(204);
    assertThat(
            post(
                "transfers",
                """
                    {"sourceAccountId":"A","destinationAccountId":"A",
                     "amount":0,"transactionId":"http:replay"}
                    """))
        .hasStatus(204);
    assertThat(balance("C")).isEqualTo(10);
    assertThat(balance("A")).isEqualTo(100000);
    assertThat(entries("http:replay")).isOne();
  }

  @Test
  void failedReplayKeepsItsOriginalCodeAfterFunding() {
    assertError(post("debits", mutation("C", "10", "http:failed")), 409, "INSUFFICIENT_FUNDS");
    assertThat(post("credits", mutation("C", "100", "http:funding"))).hasStatus(204);
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
  void businessErrorsHaveStableStatusesAndPersistFinalOutcomes() {
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
  void malformedJsonAndIdentifiersNeverCreateOutcomesOrChangeMoney() {
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
  void beanValidationHandlesBodyAndPathConstraintsWithoutPersistingOutcomes() {
    var invalidBody = post("credits", mutation(" ", "1", "http:bean"));
    assertError(invalidBody, 400, "INVALID_REQUEST");
    assertThat(invalidBody).failure().isInstanceOf(MethodArgumentNotValidException.class);
    var missingAmount = post("debits", "{\"accountId\":\"C\",\"transactionId\":\"http:bean\"}");
    assertError(missingAmount, 400, "INVALID_REQUEST");
    assertThat(missingAmount).failure().isInstanceOf(MethodArgumentNotValidException.class);
    var missingDestination =
        post(
            "transfers",
            "{\"sourceAccountId\":\"A\",\"amount\":1,\"transactionId\":\"http:bean\"}");
    assertError(missingDestination, 400, "INVALID_REQUEST");
    assertThat(missingDestination).failure().isInstanceOf(MethodArgumentNotValidException.class);
    for (String account : List.of(" ", "x".repeat(256))) {
      var invalidPath = mvc.get().uri("/api/accounts/{accountId}/balance", account).exchange();
      assertError(invalidPath, 400, "INVALID_REQUEST");
      assertThat(invalidPath).failure().isInstanceOf(HandlerMethodValidationException.class);
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Long.class)).isEqualTo(2);
    assertThat(balance("C")).isZero();
  }

  @Test
  void identifierLimitsAllow255CharactersWithoutNormalization() {
    String id = "x".repeat(255);
    assertThat(post("credits", mutation("C", "1", id))).hasStatus(204);
    assertThat(entries(id)).isOne();
    assertThat(balance("C")).isOne();
    String account = " " + "x".repeat(253) + " ";
    assertError(post("credits", mutation(account, "1", "http:boundary")), 404, "ACCOUNT_NOT_FOUND");
    assertThat(
            jdbc.queryForObject(
                "SELECT account_id FROM transactions WHERE transaction_id = ?",
                String.class,
                "http:boundary"))
        .isEqualTo(account);
  }

  @Test
  void corruptHistoryReturnsGenericServerErrorWithoutReexecution() {
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
    assertThat(response).bodyText().doesNotContain("Invalid committed", "SELECT", "stackTrace");
    assertThat(balance("C")).isZero();
    assertThat(entries("http:corrupt")).isOne();
  }

  @Test
  void databaseConsoleIsDisabledWithoutLocalProfile() {
    assertThat(get("/h2-console/")).hasStatus(404);
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

  private MvcTestResult post(String operation, String body) {
    return mvc.post()
        .uri("/api/" + operation)
        .contentType(APPLICATION_JSON)
        .content(body)
        .exchange();
  }

  private MvcTestResult get(String path) {
    return mvc.get().uri(path).exchange();
  }

  private static void assertError(MvcTestResult response, int status, String code) {
    assertThat(response)
        .hasStatus(status)
        .hasContentTypeCompatibleWith(APPLICATION_JSON)
        .bodyJson()
        .extractingPath("$.code")
        .isEqualTo(code);
  }
}
