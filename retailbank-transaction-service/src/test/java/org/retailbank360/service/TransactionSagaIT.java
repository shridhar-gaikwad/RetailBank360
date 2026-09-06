package org.retailbank360.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.client.AccountServiceClient;
import org.retailbank360.common.dto.MoneyMovementRequest;
import org.retailbank360.common.dto.MoneyMovementResponse;
import org.retailbank360.common.dto.TransferInstruction;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.InsufficientFundsException;
import org.retailbank360.constants.TransactionStatus;
import org.retailbank360.constants.TransactionType;
import org.retailbank360.dto.DepositRequest;
import org.retailbank360.dto.TransactionResponse;
import org.retailbank360.dto.TransferRequest;
import org.retailbank360.dto.WithdrawRequest;
import org.retailbank360.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The transaction saga: intent is recorded before the money moves, and the outcome is recorded
 * afterwards - including the case where the outcome was never observed.
 *
 * <p>account-service is mocked so the ledger can be made to refuse, to fail, or to disappear
 * mid-call, which is the only way to prove the saga behaves correctly in each case.</p>
 */
@SpringBootTest
class TransactionSagaIT {

    @MockitoBean
    private AccountServiceClient accountServiceClient;

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        when(accountServiceClient.accountSummary(anyLong())).thenReturn(
                Map.of("accountId", 1L, "customerId", 1L, "currency", "INR", "status", "ACTIVE"));
    }

    @Test
    @DisplayName("A successful transfer is recorded with the ledger entry references")
    void recordsASuccessfulTransfer() {
        when(accountServiceClient.transfer(any(TransferInstruction.class), anyString()))
                .thenReturn(posted("900.00"));

        TransactionResponse response = transactionService.transfer(transferRequest("100.00", "key-1"));

        assertThat(response.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(response.getTransactionType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(response.getTransactionRef()).startsWith("TXN-");
        assertThat(response.getDebitEntryRef()).isEqualTo("LEDG-D");
        assertThat(response.getCreditEntryRef()).isEqualTo("LEDG-C");
        assertThat(response.getBalanceAfter()).isEqualByComparingTo("900.00");
    }

    @Test
    @DisplayName("The transaction reference is passed to the ledger, so both sides agree")
    void passesItsOwnReferenceToTheLedger() {
        when(accountServiceClient.transfer(any(TransferInstruction.class), anyString()))
                .thenAnswer(invocation -> {
                    TransferInstruction instruction = invocation.getArgument(0);
                    assertThat(instruction.getReference()).startsWith("TXN-");
                    assertThat(instruction.getOperationId()).isEqualTo(instruction.getReference());
                    return posted("900.00");
                });

        transactionService.transfer(transferRequest("100.00", "key-ref"));
    }

    @Test
    @DisplayName("A ledger rejection marks the transaction FAILED and keeps the reason")
    void recordsALedgerRejection() {
        when(accountServiceClient.transfer(any(TransferInstruction.class), anyString()))
                .thenThrow(new InsufficientFundsException("XXXX1234", new BigDecimal("50.00"),
                        new BigDecimal("100.00")));

        assertThatThrownBy(() -> transactionService.transfer(transferRequest("100.00", "key-2")))
                .isInstanceOf(InsufficientFundsException.class);

        var recorded = transactionRepository.findAll().get(0);
        assertThat(recorded.getStatus()).isEqualTo(TransactionStatus.FAILED);
        assertThat(recorded.getFailureReason()).contains("INSUFFICIENT_FUNDS");
        assertThat(recorded.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("An unknown outcome is left PENDING rather than guessed at")
    void leavesAnUnknownOutcomePending() {
        when(accountServiceClient.transfer(any(TransferInstruction.class), anyString()))
                .thenThrow(new IllegalStateException("read timed out"));

        assertThatThrownBy(() -> transactionService.transfer(transferRequest("100.00", "key-3")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("did not confirm");

        // Marking it FAILED could hide a transfer that actually committed; marking it SUCCESS could
        // invent one that never happened. Neither guess is acceptable, so it waits for the ledger.
        assertThat(transactionRepository.findAll().get(0).getStatus()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    @DisplayName("Reconciliation settles a PENDING transaction the ledger did post")
    void reconcilesATransactionTheLedgerCommitted() {
        when(accountServiceClient.transfer(any(TransferInstruction.class), anyString()))
                .thenThrow(new IllegalStateException("connection reset"));
        assertThatThrownBy(() -> transactionService.transfer(transferRequest("100.00", "key-4")))
                .isInstanceOf(BusinessRuleViolationException.class);

        var pending = transactionRepository.findAll().get(0);
        forceStale(pending.getTransactionRef());

        when(accountServiceClient.entriesForReference(anyString())).thenReturn(List.of(
                Map.of("entryRef", "LEDG-D", "direction", "DEBIT", "balanceAfter", "900.00"),
                Map.of("entryRef", "LEDG-C", "direction", "CREDIT", "balanceAfter", "100.00")));

        assertThat(transactionService.reconcileStalePending()).isEqualTo(1);

        var settled = transactionRepository.findByTransactionRef(pending.getTransactionRef()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(settled.getDebitEntryRef()).isEqualTo("LEDG-D");
        assertThat(settled.getCreditEntryRef()).isEqualTo("LEDG-C");
    }

    @Test
    @DisplayName("Reconciliation fails a PENDING transaction the ledger never posted")
    void reconcilesATransactionTheLedgerNeverPosted() {
        when(accountServiceClient.transfer(any(TransferInstruction.class), anyString()))
                .thenThrow(new IllegalStateException("connection reset"));
        assertThatThrownBy(() -> transactionService.transfer(transferRequest("100.00", "key-5")))
                .isInstanceOf(BusinessRuleViolationException.class);

        var pending = transactionRepository.findAll().get(0);
        forceStale(pending.getTransactionRef());
        when(accountServiceClient.entriesForReference(anyString())).thenReturn(List.of());

        assertThat(transactionService.reconcileStalePending()).isEqualTo(1);
        assertThat(transactionRepository.findByTransactionRef(pending.getTransactionRef()).orElseThrow()
                .getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    @Test
    @DisplayName("Replaying an idempotency key calls the ledger only once")
    void replaysWithoutCallingTheLedgerAgain() {
        when(accountServiceClient.transfer(any(TransferInstruction.class), anyString()))
                .thenReturn(posted("900.00"));

        TransferRequest request = transferRequest("100.00", "same-key");
        TransactionResponse first = transactionService.transfer(request);
        TransactionResponse replay = transactionService.transfer(request);

        assertThat(replay.getTransactionRef()).isEqualTo(first.getTransactionRef());
        verify(accountServiceClient, times(1)).transfer(any(TransferInstruction.class), anyString());
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("A completed transfer can be reversed, and the record says so")
    void reversesACompletedTransfer() {
        when(accountServiceClient.transfer(any(TransferInstruction.class), anyString()))
                .thenReturn(posted("900.00"));
        when(accountServiceClient.reverse(anyString(), anyString(), any()))
                .thenReturn(MoneyMovementResponse.builder().reference("REV-1").status("REVERSED").build());

        TransactionResponse posted = transactionService.transfer(transferRequest("100.00", "key-6"));
        TransactionResponse reversed = transactionService.reverse(posted.getTransactionRef(), "posted in error");

        assertThat(reversed.getStatus()).isEqualTo(TransactionStatus.REVERSED);
        assertThat(reversed.getReversalReference()).isEqualTo("REV-1");
    }

    @Test
    @DisplayName("Only a successful transaction can be reversed")
    void refusesToReverseAFailedTransaction() {
        when(accountServiceClient.transfer(any(TransferInstruction.class), anyString()))
                .thenThrow(new InsufficientFundsException("XXXX1234", BigDecimal.ZERO, new BigDecimal("100.00")));
        assertThatThrownBy(() -> transactionService.transfer(transferRequest("100.00", "key-7")))
                .isInstanceOf(InsufficientFundsException.class);

        String reference = transactionRepository.findAll().get(0).getTransactionRef();
        assertThatThrownBy(() -> transactionService.reverse(reference, "why not"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only a SUCCESS transaction");
    }

    @Test
    @DisplayName("A deposit credits and a withdrawal debits, each recorded with its own type")
    void recordsDepositsAndWithdrawals() {
        when(accountServiceClient.credit(any(MoneyMovementRequest.class), anyString()))
                .thenReturn(posted("1100.00"));
        when(accountServiceClient.debit(any(MoneyMovementRequest.class), anyString()))
                .thenReturn(posted("900.00"));

        DepositRequest deposit = new DepositRequest();
        deposit.setAccountId(1L);
        deposit.setAmount(new BigDecimal("100.00"));
        deposit.setCurrency("INR");
        deposit.setIdempotencyKey("dep-1");

        WithdrawRequest withdrawal = new WithdrawRequest();
        withdrawal.setAccountId(1L);
        withdrawal.setAmount(new BigDecimal("200.00"));
        withdrawal.setCurrency("INR");
        withdrawal.setIdempotencyKey("wd-1");

        assertThat(transactionService.deposit(deposit).getTransactionType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(transactionService.withdraw(withdrawal).getTransactionType())
                .isEqualTo(TransactionType.WITHDRAWAL);

        assertThat(transactionRepository.count()).isEqualTo(2);
    }

    /**
     * Ages the record so the reconciliation sweep picks it up.
     *
     * <p>Done in SQL because {@code created_at} is mapped {@code updatable = false} - when a
     * transaction was recorded is a fact, and the application has no business rewriting it. Only a
     * test simulating the passage of time may reach around that, and it does so explicitly.</p>
     */
    private void forceStale(String transactionRef) {
        jdbcTemplate.update("update transactions set created_at = ? where transaction_ref = ?",
                java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusMinutes(10)), transactionRef);
    }

    private TransferRequest transferRequest(String amount, String idempotencyKey) {
        TransferRequest request = new TransferRequest();
        request.setFromAccountId(1L);
        request.setToAccountId(2L);
        request.setAmount(new BigDecimal(amount));
        request.setCurrency("INR");
        request.setDescription("test transfer");
        request.setIdempotencyKey(idempotencyKey);
        return request;
    }

    private MoneyMovementResponse posted(String balanceAfter) {
        return MoneyMovementResponse.builder()
                .reference("LEDG-REF")
                .status("POSTED")
                .accountId(1L)
                .balanceAfter(new BigDecimal(balanceAfter))
                .debitEntryRef("LEDG-D")
                .creditEntryRef("LEDG-C")
                .build();
    }
}
