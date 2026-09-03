package org.retailbank360.service;

import org.retailbank360.entity.Account;
import java.util.List;

public interface AccountService {

    Account createAccount(Account account);

    List<Account> getAllAccounts();

    Account getAccountById(Long id);

    Account updateAccount(Long id, Account account);

    void deleteAccount(Long id);
}
