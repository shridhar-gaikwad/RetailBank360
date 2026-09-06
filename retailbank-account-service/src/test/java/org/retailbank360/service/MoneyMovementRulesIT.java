package org.retailbank360.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.client.CustomerServiceClient;
import org.retailbank360.common.dto.MoneyMovementRequest;
import org.retailbank360.common.dto.MoneyMovementResponse;
import org.retailbank360.common.dto.TransferInstruction;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.InsufficientFundsException;
import org.retailbank360.constants.AccountStatus;
import org.retailbank360.constants.MovementType;
import org.retailbank360.entity.Account;
import org.retailbank360.entity.LedgerEntry;
import org.retailbank360.repository.AccountRepository;
import org.retailbank360.repository.LedgerEntryRepository;
import org.retailbank360.support.AccountTestFixtures;
import org.retailbank360.support.DatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The financial-correctness rules the requirement lists, exercised against the real ledger. */
@SpringBootTest
class MoneyMovementRulesIT {

    @MockitoBean
    private CustomerServiceClient customerServiceClient;

    @Autowired
    private MoneyMovementService moneyMovementService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    @DisplayName("A non-overdraft account cannot be pushed below its minimum balance")
    void enforcesMinimumBalance() {
        Account account = AccountTestFixtures.savingsWithMinimum(accountRepository, "5000.00", "1000.00");

        assertThatThrownBy(() -> debit(account, "4500.00", "TXN-MIN"))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining("available 4000.00");

        // The floor is exactly reachable, and not a rupee further.
        MoneyMovementResponse allowed = debit(account, "4000.00", "TXN-MIN-OK");
        assertThat(allowed.getBalanceAfter()).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("An overdraft account may go negative, but only as far as its agreed limit")
    void enforcesOverdraftLimit() {
        Account account = AccountTestFixtures.overdraft(accountRepository, "1000.00", "5000.00");

        MoneyMovementResponse allowed = debit(account, "6000.00", "TXN-OD-OK");
        assertThat(allowed.getBalanceAfter()).isEqualByComparingTo("-5000.00");

        assertThatThrownBy(() -> debit(account, "0.01", "TXN-OD-OVER"))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    @DisplayName("Transfers out are capped by the daily limit")
    void enforcesDailyTransferLimit() {
        Account source = AccountTestFixtures.savings(accountRepository, "100000.00");
        Account target = AccountTestFixtures.savings(accountRepository, "0.00");
        source.setDailyTransferLimit(new BigDecimal("5000.00"));
        accountRepository.save(source);

        transfer(source, target, "3000.00", "TXN-DAY-1");
        transfer(source, target, "2000.00", "TXN-DAY-2");

        assertThatThrownBy(() -> transfer(source, target, "0.01", "TXN-DAY-3"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("daily limit");

        assertThat(accountRepository.findById(source.getId()).orElseThrow().getTransferredToday())
                .isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("A frozen account accepts nothing; a dormant one still accepts credits")
    void enforcesAccountStatus() {
        Account frozen = AccountTestFixtures.savings(accountRepository, "1000.00");
        frozen.setStatus(AccountStatus.FROZEN);
        accountRepository.save(frozen);

        assertThatThrownBy(() -> debit(frozen, "10.00", "TXN-FROZEN-D"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot be debited");
        assertThatThrownBy(() -> credit(frozen, "10.00", "TXN-FROZEN-C"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("cannot be credited");

        Account dormant = AccountTestFixtures.savings(accountRepository, "1000.00");
        dormant.setStatus(AccountStatus.INACTIVE);
        accountRepository.save(dormant);

        // Money can still arrive for a dormant customer; it just cannot leave.
        assertThat(credit(dormant, "10.00", "TXN-DORMANT-C").getBalanceAfter()).isEqualByComparingTo("1010.00");
        assertThatThrownBy(() -> debit(dormant, "10.00", "TXN-DORMANT-D"))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    @DisplayName("Cross-currency transfers are refused rather than silently converted")
    void refusesCurrencyMismatch() {
        Account rupees = AccountTestFixtures.savings(accountRepository, "1000.00");
        Account dollars = AccountTestFixtures.savings(accountRepository, "1000.00");
        dollars.setCurrency("USD");
        accountRepository.save(dollars);

        assertThatThrownBy(() -> transfer(rupees, dollars, "100.00", "TXN-FX"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cross-currency");
    }

    @Test
    @DisplayName("A transfer to the same account is refused")
    void refusesSelfTransfer() {
        Account account = AccountTestFixtures.savings(accountRepository, "1000.00");

        assertThatThrownBy(() -> transfer(account, account, "100.00", "TXN-SELF"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("two different accounts");
    }

    @Test
    @DisplayName("A zero or negative amount never reaches the ledger")
    void refusesNonPositiveAmounts() {
        Account account = AccountTestFixtures.savings(accountRepository, "1000.00");

        assertThatThrownBy(() -> credit(account, "0.00", "TXN-ZERO"))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> debit(account, "-50.00", "TXN-NEG"))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThat(ledgerRepository.count()).isZero();
    }

    @Test
    @DisplayName("Replaying an idempotency key returns the original result without moving money again")
    void replaysAnIdempotentTransfer() {
        Account source = AccountTestFixtures.savings(accountRepository, "1000.00");
        Account target = AccountTestFixtures.savings(accountRepository, "0.00");

        TransferInstruction instruction = TransferInstruction.builder()
                .fromAccountId(source.getId())
                .toAccountId(target.getId())
                .amount(new BigDecimal("300.00"))
                .currency("INR")
                .reference("TXN-REPLAY")
                .operationId("replay-test")
                .initiatedBy("test")
                .build();

        MoneyMovementResponse first = moneyMovementService.transfer(instruction, "replay-key");
        MoneyMovementResponse replay = moneyMovementService.transfer(instruction, "replay-key");

        assertThat(replay.getDebitEntryRef()).isEqualTo(first.getDebitEntryRef());
        assertThat(accountRepository.findById(source.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("700.00");
        assertThat(ledgerRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("A reversal restores the balances and leaves the original entries in place")
    void reversalCompensatesWithoutDeletingHistory() {
        Account source = AccountTestFixtures.savings(accountRepository, "1000.00");
        Account target = AccountTestFixtures.savings(accountRepository, "0.00");

        transfer(source, target, "250.00", "TXN-TO-REVERSE");
        moneyMovementService.reverse("TXN-TO-REVERSE", "posted in error",
                List.of(source.getId(), target.getId()));

        assertThat(accountRepository.findById(source.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1000.00");
        assertThat(accountRepository.findById(target.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("0.00");

        // The originals survive; the undo is expressed as two further entries, not as a deletion.
        assertThat(ledgerRepository.findByReferenceOrderByPostedAtAsc("TXN-TO-REVERSE")).hasSize(2);
        assertThat(ledgerRepository.count()).isEqualTo(4);
        assertThat(ledgerRepository.findAll())
                .filteredOn(entry -> entry.getMovementType() == MovementType.REVERSAL)
                .hasSize(2)
                .allSatisfy(entry -> assertThat(entry.getReversesEntryRef()).isNotBlank());
    }

    @Test
    @DisplayName("Reversing the same reference twice is refused")
    void refusesDoubleReversal() {
        Account source = AccountTestFixtures.savings(accountRepository, "1000.00");
        Account target = AccountTestFixtures.savings(accountRepository, "0.00");

        transfer(source, target, "100.00", "TXN-ONE-REVERSAL");
        moneyMovementService.reverse("TXN-ONE-REVERSAL", "first", List.of(source.getId(), target.getId()));

        assertThatThrownBy(() -> moneyMovementService.reverse("TXN-ONE-REVERSAL", "second",
                List.of(source.getId(), target.getId())))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already been reversed");
    }

    @Test
    @DisplayName("The ledger records the balance that followed every entry")
    void ledgerCarriesTheRunningBalance() {
        Account account = AccountTestFixtures.savings(accountRepository, "1000.00");

        credit(account, "500.00", "TXN-L1");
        debit(account, "200.00", "TXN-L2");
        credit(account, "50.00", "TXN-L3");

        List<LedgerEntry> entries = ledgerRepository.findByAccountIdOrderByPostedAtDesc(
                account.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent();

        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).getBalanceAfter()).isEqualByComparingTo("1350.00");
        assertThat(accountRepository.findById(account.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("1350.00");
    }

    private MoneyMovementResponse credit(Account account, String amount, String reference) {
        return moneyMovementService.credit(movement(account, amount, reference, "DEPOSIT"), reference);
    }

    private MoneyMovementResponse debit(Account account, String amount, String reference) {
        return moneyMovementService.debit(movement(account, amount, reference, "WITHDRAWAL"), reference);
    }

    private MoneyMovementRequest movement(Account account, String amount, String reference, String type) {
        return MoneyMovementRequest.builder()
                .accountId(account.getId())
                .amount(new BigDecimal(amount))
                .currency("INR")
                .movementType(type)
                .reference(reference)
                .operationId(reference)
                .initiatedBy("test")
                .build();
    }

    private MoneyMovementResponse transfer(Account from, Account to, String amount, String reference) {
        return moneyMovementService.transfer(TransferInstruction.builder()
                .fromAccountId(from.getId())
                .toAccountId(to.getId())
                .amount(new BigDecimal(amount))
                .currency("INR")
                .reference(reference)
                .operationId(reference)
                .initiatedBy("test")
                .build(), reference);
    }
}
