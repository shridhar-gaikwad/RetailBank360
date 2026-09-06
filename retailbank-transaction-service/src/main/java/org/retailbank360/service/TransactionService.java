package org.retailbank360.service;

import org.retailbank360.dto.DepositRequest;
import org.retailbank360.dto.TransactionResponse;
import org.retailbank360.dto.TransferRequest;
import org.retailbank360.dto.WithdrawRequest;

import java.util.List;

/** Deposit, withdrawal and transfer orchestration. */
public interface TransactionService {

    TransactionResponse deposit(DepositRequest request);

    TransactionResponse withdraw(WithdrawRequest request);

    TransactionResponse transfer(TransferRequest request);

    TransactionResponse getTransaction(String transactionRef);

    List<TransactionResponse> getAllTransactions(int page, int size);

    List<TransactionResponse> getTransactionsByAccount(Long accountId, int page, int size);

    /**
     * Compensating action: reverses a completed transaction.
     *
     * <p>Nothing is deleted. account-service writes mirror-image ledger entries and this record moves
     * to {@code REVERSED}, so both the original movement and its undo remain visible.</p>
     */
    TransactionResponse reverse(String transactionRef, String reason);

    /**
     * Settles transactions left {@code PENDING} because their ledger call never returned an
     * observable outcome. Asks account-service what actually happened and closes them out.
     *
     * @return number of transactions settled
     */
    int reconcileStalePending();
}
