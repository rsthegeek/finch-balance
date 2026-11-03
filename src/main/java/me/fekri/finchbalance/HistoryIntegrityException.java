package me.fekri.finchbalance;

/** Committed history is incomplete or inconsistent and cannot be safely replayed. */
public class HistoryIntegrityException extends IllegalStateException {
  /** Reports an invalid committed entry group without attempting to repair or reexecute it. */
  public HistoryIntegrityException() {
    super("Invalid committed transaction entry group");
  }
}
