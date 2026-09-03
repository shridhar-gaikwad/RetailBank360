package org.retailbank360.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.retailbank360.entity.Account;
import org.retailbank360.service.AccountService;
import org.retailbank360.service.AccountServiceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountServiceImpl accountServiceImpl;

    public AccountController(AccountServiceImpl accountServiceImpl) {
        this.accountServiceImpl = accountServiceImpl;
    }

    // Create Account
    @PostMapping
    public ResponseEntity<Account> createAccount(@Valid @RequestBody Account account) {
        Account createdAccount = accountServiceImpl.createAccount(account);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(createdAccount);
    }

    // Get All Accounts
    @GetMapping("/all")
    public ResponseEntity<List<Account>> getAllAccounts() {
        List<Account> accounts = accountServiceImpl.getAllAccounts();
        return ResponseEntity.ok(accounts);
    }

    // Get Account By ID
    @GetMapping("/{id}")
    public ResponseEntity<Account> getAccountById(@PathVariable Long id) {
        Account account = accountServiceImpl.getAccountById(id);
        return ResponseEntity.ok(account);
    }

    // Update Account
    @PutMapping("/{id}")
    public ResponseEntity<Account> updateAccount(@PathVariable Long id, @Valid @RequestBody Account account) {
        Account updatedAccount = accountServiceImpl.updateAccount(id, account);
        return ResponseEntity.ok(updatedAccount);
    }

    // Delete Account
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAccount(@PathVariable Long id) {
        accountServiceImpl.deleteAccount(id);
        return ResponseEntity.noContent().build();
    }
}
