package me.fekri.finchbalance.dto;

/** Stable error code and public explanation without persistence implementation details. */
public record ApiError(String code, String message) {}
