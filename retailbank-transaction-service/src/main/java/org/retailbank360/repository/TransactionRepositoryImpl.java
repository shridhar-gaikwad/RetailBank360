package org.retailbank360.repository;

import org.retailbank360.entity.Transaction;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class TransactionRepositoryImpl implements TransactionRepository {
    @Override
    public Transaction save(Transaction transaction) {
        return null;
    }

    @Override
    public Optional<Transaction> findById(String transactionId) {
        return Optional.empty();
    }

    @Override
    public List<Transaction> findAll() {
        return List.of();
    }

    @Override
    public List<Transaction> findByAccountNumber(Long accountNumber) {
        return List.of();
    }
}
