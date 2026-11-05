package me.fekri.finchbalance.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import me.fekri.finchbalance.exception.BalanceException;
import me.fekri.finchbalance.model.FailureCode;
import me.fekri.finchbalance.model.OperationType;
import me.fekri.finchbalance.validation.Identifiers;
import org.junit.jupiter.api.Test;

class RequestValidationTest {
  @Test
  void rejectsEveryMalformedIdentifierPositionBeforeBusinessValidation() {
    for (String malformed : Arrays.asList(null, "", " \t\n", "\u2003", "x".repeat(256))) {
      assertThatThrownBy(() -> Identifiers.requireValid(malformed))
          .isInstanceOf(IllegalArgumentException.class);
      for (OperationType type : OperationType.values()) {
        String destination = type == OperationType.TRANSFER ? "B" : null;
        assertThatThrownBy(() -> new OperationRequest(type, malformed, destination, -1, "id"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperationRequest(type, "A", destination, -1, malformed))
            .isInstanceOf(IllegalArgumentException.class);
      }
      assertThatThrownBy(
              () -> new OperationRequest(OperationType.TRANSFER, "A", malformed, -1, "id"))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void acceptsEnvelopeBoundariesWithoutNormalizingOrEvaluatingBusinessRules() {
    String boundary = "x".repeat(255);
    for (OperationType type : OperationType.values()) {
      String destination = type == OperationType.TRANSFER ? boundary : null;
      var request = new OperationRequest(type, " a ", destination, Long.MIN_VALUE, boundary);
      assertThat(request)
          .isEqualTo(new OperationRequest(type, " a ", destination, Long.MIN_VALUE, boundary));
      assertThat(request.accountIds()).contains(" a ");
      assertThat(request.transactionId()).isEqualTo(boundary);
      assertThat(request.amount()).isEqualTo(Long.MIN_VALUE);
    }
    assertThatCode(() -> new OperationRequest(OperationType.TRANSFER, "A", "A", 0, " id "))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> new OperationRequest(OperationType.CREDIT, "A", "B", 1, "id"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void outcomeReportsTheStableFailureCodeAndSuccessReturnsNormally() {
    assertThatCode(() -> OperationOutcome.success().report()).doesNotThrowAnyException();
    for (FailureCode code : FailureCode.values()) {
      assertThatThrownBy(() -> OperationOutcome.failed(code).report())
          .isInstanceOfSatisfying(
              BalanceException.class, failure -> assertThat(failure.code()).isEqualTo(code));
    }
  }
}
