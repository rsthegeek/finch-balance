package me.fekri.finchbalance.dto;

/** Committed account balance in integer minor units. */
public record AccountBalance(String accountId, long balance) {}
