package org.retailbank360.repository;

import org.retailbank360.entity.Transaction;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository {

    Transaction save(Transaction transaction);

    Optional<Transaction> findById(String transactionId);

    List<Transaction> findAll();

    List<Transaction> findByAccountNumber(Long accountNumber);

}