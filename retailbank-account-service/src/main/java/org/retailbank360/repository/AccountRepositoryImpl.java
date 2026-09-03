package org.retailbank360.repository;

import org.retailbank360.entity.Account;
import java.util.List;
import java.util.Optional;

public class AccountRepositoryImpl implements AccountRepository {

    @Override
    public Account save(Account account) {
        return null;
    }

    public Optional<Object> findById(Long id) {
        return Optional.of(new Account());
    }

    @Override
    public Optional<Account> findByAccountNumber(Long accountNumber) {
        return Optional.empty();
    }

    @Override
    public List<Account> findAll() {
        return List.of();
    }

    @Override
    public List<Account> findByCustomerId(Long customerId) {
        return List.of();
    }

    @Override
    public void delete(Long accountNumber) {

    }
}
