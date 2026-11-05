package me.fekri.finchbalance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** JSON credit/debit envelope; business validation remains behind stored-outcome lookup. */
public record BalanceMutationRequest(
    @NotBlank @Size(max = 255) String accountId,
    @NotNull Long amount,
    @NotBlank @Size(max = 255) String transactionId) {}
