package me.fekri.finchbalance.service;

import java.util.List;
import java.util.Objects;
import me.fekri.finchbalance.dto.OperationOutcome;
import me.fekri.finchbalance.entity.TransactionEntry;
import me.fekri.finchbalance.exception.HistoryIntegrityException;
import me.fekri.finchbalance.model.EntryRole;
import me.fekri.finchbalance.model.EntryStatus;
import me.fekri.finchbalance.model.OperationType;
import org.springframework.stereotype.Component;

/** Decodes terminal history independently of the incoming retry payload. */
@Component
class StoredOutcomes {
  OperationOutcome decode(List<TransactionEntry> entries) {
    require(!entries.isEmpty() && entries.size() <= 2);
    String originalId = entries.getFirst().id().transactionId();
    for (var entry : entries) {
      require(validIdentifier(entry.accountId()) && validIdentifier(originalId));
      require(originalId.equals(entry.id().transactionId()) && entry.createdAt() != null);
      require(entry.type() != null && entry.status() != null);
      if (entry.status() == EntryStatus.SUCCEEDED) {
        require(entry.amount() > 0 && entry.failureCode() == null);
      } else {
        require(entry.failureCode() != null && entry.id().role() != EntryRole.DESTINATION);
      }
      if (entry.type() == OperationType.TRANSFER) {
        require(
            entry.id().role() != EntryRole.SINGLE
                && validIdentifier(entry.counterpartyAccountId()));
        require(
            entry.status() == EntryStatus.FAILED
                || !entry.accountId().equals(entry.counterpartyAccountId()));
      } else {
        require(entry.id().role() == EntryRole.SINGLE && entry.counterpartyAccountId() == null);
      }
    }
    if (entries.size() == 1) {
      var entry = entries.getFirst();
      require(
          entry.id().role() == EntryRole.SINGLE
              || (entry.id().role() == EntryRole.SOURCE && entry.status() == EntryStatus.FAILED));
      return new OperationOutcome(entry.failureCode());
    }
    var source =
        entries.stream()
            .filter(e -> e.id().role() == EntryRole.SOURCE)
            .findFirst()
            .orElseThrow(HistoryIntegrityException::new);
    var destination =
        entries.stream()
            .filter(e -> e.id().role() == EntryRole.DESTINATION)
            .findFirst()
            .orElseThrow(HistoryIntegrityException::new);
    require(
        source.status() == EntryStatus.SUCCEEDED && destination.status() == EntryStatus.SUCCEEDED);
    require(
        source.type() == OperationType.TRANSFER && destination.type() == OperationType.TRANSFER);
    require(source.amount() == destination.amount());
    require(
        Objects.equals(source.accountId(), destination.counterpartyAccountId())
            && Objects.equals(destination.accountId(), source.counterpartyAccountId()));
    require(source.createdAt().equals(destination.createdAt()));
    return OperationOutcome.success();
  }

  private static boolean validIdentifier(String value) {
    return value != null && !value.isBlank() && value.length() <= 255;
  }

  private static void require(boolean valid) {
    if (!valid) {
      throw new HistoryIntegrityException();
    }
  }
}
