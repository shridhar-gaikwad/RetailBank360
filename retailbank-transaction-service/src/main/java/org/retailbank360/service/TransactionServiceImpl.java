package org.retailbank360.service;

import org.retailbank360.dto.DepositRequest;
import org.retailbank360.dto.TransactionResponse;
import org.retailbank360.dto.TransferRequest;
import org.retailbank360.dto.WithdrawRequest;
import java.util.List;

public class TransactionServiceImpl implements TransactionService {

    @Override
    public TransactionResponse deposit(DepositRequest request) {
        return null;
    }

    @Override
    public TransactionResponse withdraw(WithdrawRequest request) {
        return null;
    }

    @Override
    public TransactionResponse transfer(TransferRequest request) {
        return null;
    }

    @Override
    public TransactionResponse getTransaction(String transactionId) {
        return null;
    }

    @Override
    public List<TransactionResponse> getAllTransactions() {
        return List.of();
    }

    @Override
    public List<TransactionResponse> getTransactionsByAccount(Long accountNumber) {
        return List.of();
    }
}
