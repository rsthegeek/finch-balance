package me.fekri.finchbalance.controller;

import me.fekri.finchbalance.dto.ApiError;
import me.fekri.finchbalance.exception.BalanceException;
import me.fekri.finchbalance.exception.HistoryIntegrityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates service outcomes after completion without wrapping the controller in a transaction.
 */
@RestControllerAdvice(assignableTypes = BalanceController.class)
public class BalanceErrorHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(BalanceErrorHandler.class);

  /** Preserves the service's stable business code, including on failed replay. */
  @ExceptionHandler(BalanceException.class)
  public ResponseEntity<ApiError> businessFailure(BalanceException failure) {
    HttpStatus status =
        switch (failure.code()) {
          case INVALID_AMOUNT, SAME_ACCOUNT_TRANSFER -> HttpStatus.BAD_REQUEST;
          case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;
          case INSUFFICIENT_FUNDS, BALANCE_OVERFLOW -> HttpStatus.CONFLICT;
        };
    return ResponseEntity.status(status)
        .body(new ApiError(failure.code().name(), failure.getMessage()));
  }

  /** Reports malformed envelopes without inventing a persisted business outcome. */
  @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
  public ResponseEntity<ApiError> invalidRequest() {
    return ResponseEntity.badRequest()
        .body(
            new ApiError(
                "INVALID_REQUEST",
                "Provide valid identifiers and an explicit integer amount within the long range"));
  }

  /** Keeps infrastructure failures distinct from committed business rejections. */
  @ExceptionHandler({
    HistoryIntegrityException.class,
    DataAccessException.class,
    TransactionException.class
  })
  public ResponseEntity<ApiError> internalFailure(RuntimeException failure) {
    LOGGER.error("Balance request failed", failure);
    return ResponseEntity.internalServerError()
        .body(new ApiError("INTERNAL_ERROR", "The balance request could not be completed"));
  }
}
