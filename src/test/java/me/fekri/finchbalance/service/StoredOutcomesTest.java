package me.fekri.finchbalance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import me.fekri.finchbalance.dto.OperationOutcome;
import me.fekri.finchbalance.entity.TransactionEntry;
import me.fekri.finchbalance.exception.HistoryIntegrityException;
import me.fekri.finchbalance.model.EntryRole;
import me.fekri.finchbalance.model.EntryStatus;
import me.fekri.finchbalance.model.FailureCode;
import me.fekri.finchbalance.model.OperationType;
import org.junit.jupiter.api.Test;

class StoredOutcomesTest {
  private final StoredOutcomes decoder = new StoredOutcomes();

  @Test
  void decodesEachValidGroupAndDoesNotDependOnPairOrdering() {
    for (OperationType type : List.of(OperationType.CREDIT, OperationType.DEBIT)) {
      assertThat(decoder.decode(List.of(entry(EntryRole.SINGLE, "A", null, 1, type, null))))
          .isEqualTo(OperationOutcome.success());
      for (FailureCode code : FailureCode.values()) {
        assertThat(
                decoder.decode(List.of(entry(EntryRole.SINGLE, "missing", null, -1, type, code))))
            .isEqualTo(OperationOutcome.failed(code));
      }
    }
    var source = entry(EntryRole.SOURCE, "A", "B", 9, OperationType.TRANSFER, null);
    var destination = entry(EntryRole.DESTINATION, "B", "A", 9, OperationType.TRANSFER, null);
    assertThat(decoder.decode(List.of(source, destination))).isEqualTo(OperationOutcome.success());
    assertThat(decoder.decode(List.of(destination, source))).isEqualTo(OperationOutcome.success());
    assertThat(
            decoder.decode(
                List.of(
                    entry(
                        EntryRole.SOURCE,
                        "missing",
                        "missing",
                        1,
                        OperationType.TRANSFER,
                        FailureCode.SAME_ACCOUNT_TRANSFER))))
        .isEqualTo(OperationOutcome.failed(FailureCode.SAME_ACCOUNT_TRANSFER));
  }

  @Test
  void rejectsIncompleteMixedAndMismatchedCommittedGroups() {
    var source = entry(EntryRole.SOURCE, "A", "B", 9, OperationType.TRANSFER, null);
    var destination = entry(EntryRole.DESTINATION, "B", "A", 9, OperationType.TRANSFER, null);
    final var single = entry(EntryRole.SINGLE, "A", null, 9, OperationType.CREDIT, null);
    reject(List.of());
    reject(List.of(source));
    reject(List.of(destination));
    reject(List.of(source, single));
    reject(List.of(source, destination, single));
    reject(List.of(single, single));
    reject(
        List.of(source, entry(EntryRole.DESTINATION, "B", "A", 8, OperationType.TRANSFER, null)));
    reject(
        List.of(source, entry(EntryRole.DESTINATION, "C", "A", 9, OperationType.TRANSFER, null)));
    reject(
        List.of(source, entry(EntryRole.DESTINATION, "B", "C", 9, OperationType.TRANSFER, null)));
    reject(List.of(source, entry(EntryRole.DESTINATION, "B", "A", 9, OperationType.DEBIT, null)));
    reject(
        List.of(
            source,
            entry(
                EntryRole.DESTINATION,
                "B",
                "A",
                9,
                OperationType.TRANSFER,
                FailureCode.INSUFFICIENT_FUNDS)));
    reject(
        List.of(
            new TransactionEntry(
                "other",
                EntryRole.SOURCE,
                "A",
                "B",
                9,
                OperationType.TRANSFER,
                EntryStatus.SUCCEEDED,
                null,
                source.createdAt()),
            destination));
    reject(
        List.of(
            source,
            new TransactionEntry(
                "id",
                EntryRole.DESTINATION,
                "B",
                "A",
                9,
                OperationType.TRANSFER,
                EntryStatus.SUCCEEDED,
                null,
                source.createdAt().plusSeconds(1))));
    reject(List.of(entry(EntryRole.SINGLE, "A", "B", 9, OperationType.CREDIT, null)));
    reject(List.of(entry(EntryRole.SINGLE, "A", null, 0, OperationType.CREDIT, null)));
    reject(
        List.of(entry(EntryRole.SOURCE, "A", "A", 9, OperationType.TRANSFER, null), destination));
  }

  private void reject(List<TransactionEntry> entries) {
    assertThatThrownBy(() -> decoder.decode(entries)).isInstanceOf(HistoryIntegrityException.class);
  }

  private static TransactionEntry entry(
      EntryRole role,
      String account,
      String counterparty,
      long amount,
      OperationType type,
      FailureCode failure) {
    return new TransactionEntry(
        "id",
        role,
        account,
        counterparty,
        amount,
        type,
        failure == null ? EntryStatus.SUCCEEDED : EntryStatus.FAILED,
        failure,
        Instant.parse("2026-01-01T00:00:00Z"));
  }
}
