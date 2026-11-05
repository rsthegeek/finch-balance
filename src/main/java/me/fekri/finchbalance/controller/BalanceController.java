package me.fekri.finchbalance.controller;

import me.fekri.finchbalance.dto.AccountBalance;
import me.fekri.finchbalance.dto.BalanceMutation;
import me.fekri.finchbalance.dto.TransferMutation;
import me.fekri.finchbalance.service.BalanceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** HTTP adapter delegating all money, idempotency, and transaction rules to BalanceService. */
@RestController
@RequestMapping("/api")
public class BalanceController {
  private final BalanceService balances;

  /** Creates an adapter using the shared service and its singleton lock registries. */
  public BalanceController(BalanceService balances) {
    this.balances = balances;
  }

  /** Returns an account-locked committed balance. */
  @GetMapping("/accounts/{accountId}/balance")
  public AccountBalance getBalance(@PathVariable String accountId) {
    return new AccountBalance(accountId, balances.getBalance(accountId));
  }

  /** Credits the account or replays the original outcome without adding money twice. */
  @PostMapping("/credits")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void credit(@RequestBody BalanceMutation request) {
    balances.credit(request.accountId(), request.amount(), request.transactionId());
  }

  /** Debits the account or reports its previously committed rejection. */
  @PostMapping("/debits")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void debit(@RequestBody BalanceMutation request) {
    balances.debit(request.accountId(), request.amount(), request.transactionId());
  }

  /** Transfers atomically or replays the original transaction's outcome. */
  @PostMapping("/transfers")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void transfer(@RequestBody TransferMutation request) {
    balances.transfer(
        request.sourceAccountId(),
        request.destinationAccountId(),
        request.amount(),
        request.transactionId());
  }
}
