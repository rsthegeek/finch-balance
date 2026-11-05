package me.fekri.finchbalance.validation;

/** Shared identifier-envelope rules applied before acquiring locks. */
public final class Identifiers {
  private Identifiers() {}

  /** Rejects null, blank, or overlong identifiers without normalizing the submitted value. */
  public static void requireValid(String value) {
    if (value == null || value.isBlank() || value.length() > 255) {
      throw new IllegalArgumentException("Identifiers must be nonblank and at most 255 characters");
    }
  }
}
