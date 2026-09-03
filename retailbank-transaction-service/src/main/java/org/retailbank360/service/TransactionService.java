package org.retailbank360.service;

import org.retailbank360.dto.DepositRequest;
import org.retailbank360.dto.TransactionResponse;
import org.retailbank360.dto.TransferRequest;
import org.retailbank360.dto.WithdrawRequest;
import java.util.List;

public interface TransactionService {

    TransactionResponse deposit(DepositRequest request);

    TransactionResponse withdraw(WithdrawRequest request);

    TransactionResponse transfer(TransferRequest request);

    TransactionResponse getTransaction(String transactionId);

    List<TransactionResponse> getAllTransactions();

    List<TransactionResponse> getTransactionsByAccount(Long accountNumber);
}
