package me.fekri.finchbalance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** JSON transfer envelope; amount and account-state rules are evaluated by the service. */
public record TransferMutationRequest(
    @NotBlank @Size(max = 255) String sourceAccountId,
    @NotBlank @Size(max = 255) String destinationAccountId,
    @NotNull Long amount,
    @NotBlank @Size(max = 255) String transactionId) {}
