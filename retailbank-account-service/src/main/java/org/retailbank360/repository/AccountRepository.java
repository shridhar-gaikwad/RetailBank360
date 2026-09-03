package org.retailbank360.repository;

import org.retailbank360.entity.Account;
import java.util.List;
import java.util.Optional;

public interface AccountRepository {

    Account save(Account account);

    Optional<Account> findByAccountNumber(Long accountNumber);

    List<Account> findAll();

    List<Account> findByCustomerId(Long customerId);

    void delete(Long accountNumber);

}
