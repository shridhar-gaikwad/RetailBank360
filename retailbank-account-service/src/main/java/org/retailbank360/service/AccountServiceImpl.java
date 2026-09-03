package org.retailbank360.service;

import org.retailbank360.constants.AccountStatus;
import org.retailbank360.entity.Account;
import org.retailbank360.repository.AccountRepositoryImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class AccountServiceImpl implements AccountService {

    private final AccountRepositoryImpl accountRepositoryImpl;

    public AccountServiceImpl(AccountRepositoryImpl accountRepositoryImpl) {
        this.accountRepositoryImpl = accountRepositoryImpl;
    }

    @Override
    public Account createAccount(Account account) {

        LocalDateTime now = LocalDateTime.now();

        account.setCreatedAt(now);
        account.setUpdatedAt(now);

        if (account.getStatus() == null) {
            account.setStatus(AccountStatus.ACTIVE);
        }

        if (account.getBalance() == null) {
            account.setBalance(java.math.BigDecimal.ZERO);
        }

        return accountRepositoryImpl.save(account);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Account> getAllAccounts() {
        return accountRepositoryImpl.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public Account getAccountById(Long id) {

        return (Account) accountRepositoryImpl.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Account not found with id: " + id));
    }

    @Override
    @Transactional
    public Account updateAccount(Long id, Account account) {

        Account existingAccount = (Account) accountRepositoryImpl.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Account not found with id: " + id));

        existingAccount.setCustomerId(account.getCustomerId());
        existingAccount.setAccountType(account.getAccountType());
        existingAccount.setBalance(account.getBalance());
        existingAccount.setStatus(account.getStatus());
        existingAccount.setCurrency(account.getCurrency());

        existingAccount.setUpdatedAt(LocalDateTime.now());

        return accountRepositoryImpl.save(existingAccount);
    }

    @Override
    @Transactional
    public void deleteAccount(Long id) {

        Account existingAccount = (Account) accountRepositoryImpl.findById(id)
                .orElseThrow(() ->
                        new RuntimeException("Account not found with id: " + id));

        accountRepositoryImpl.delete(existingAccount.getId());
    }
}

