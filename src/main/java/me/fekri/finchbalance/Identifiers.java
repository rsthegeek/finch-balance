package me.fekri.finchbalance;

final class Identifiers {
  private Identifiers() {}

  static void requireValid(String value) {
    if (value == null || value.isBlank() || value.length() > 255) {
      throw new IllegalArgumentException("Identifiers must be nonblank and at most 255 characters");
    }
  }
}
