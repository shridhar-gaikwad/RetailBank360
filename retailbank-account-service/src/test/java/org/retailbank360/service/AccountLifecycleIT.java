package org.retailbank360.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.client.CustomerServiceClient;
import org.retailbank360.client.dto.CustomerProfile;
import org.retailbank360.common.dto.MoneyMovementRequest;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.ExternalServiceException;
import org.retailbank360.constants.AccountStatus;
import org.retailbank360.constants.AccountType;
import org.retailbank360.dto.AccountLimitsRequest;
import org.retailbank360.dto.AccountRequest;
import org.retailbank360.dto.AccountResponse;
import org.retailbank360.dto.AccountStatusRequest;
import org.retailbank360.dto.StatementResponse;
import org.retailbank360.entity.Account;
import org.retailbank360.repository.AccountRepository;
import org.retailbank360.support.AccountTestFixtures;
import org.retailbank360.support.DatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/** Account opening, the KYC gate, limit changes and statements. */
@SpringBootTest
class AccountLifecycleIT {

    @MockitoBean
    private CustomerServiceClient customerServiceClient;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountStatementService statementService;

    @Autowired
    private MoneyMovementService moneyMovementService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    @DisplayName("An account can only be opened for a customer whose KYC is verified")
    void refusesToOpenAnAccountWithoutKyc() {
        when(customerServiceClient.getProfile(anyLong())).thenReturn(profile(false, true));

        assertThatThrownBy(() -> accountService.createAccount(request(null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("KYC");

        assertThat(accountRepository.count()).isZero();
    }

    @Test
    @DisplayName("An account cannot be opened for a blocked customer")
    void refusesToOpenAnAccountForABlockedCustomer() {
        when(customerServiceClient.getProfile(anyLong())).thenReturn(profile(true, false));

        assertThatThrownBy(() -> accountService.createAccount(request(null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("CLOSED");
    }

    @Test
    @DisplayName("If the KYC check cannot be reached, opening fails closed")
    void failsClosedWhenTheKycCheckIsUnavailable() {
        when(customerServiceClient.getProfile(anyLong())).thenThrow(new IllegalStateException("connection refused"));

        // Opening an account without a verified KYC file because a downstream call timed out would
        // defeat the control entirely, so the request is refused rather than waved through.
        assertThatThrownBy(() -> accountService.createAccount(request(null)))
                .isInstanceOf(ExternalServiceException.class);
        assertThat(accountRepository.count()).isZero();
    }

    @Test
    @DisplayName("Opening with a deposit funds the account through a real ledger entry")
    void opensAndFundsAnAccount() {
        when(customerServiceClient.getProfile(anyLong())).thenReturn(profile(true, true));

        AccountResponse opened = accountService.createAccount(request(new BigDecimal("5000.00")));

        assertThat(opened.getBalance()).isEqualByComparingTo("5000.00");
        assertThat(opened.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(opened.getMaskedAccountNumber()).startsWith("XXXX");
        assertThat(opened.getMinimumBalance())
                .as("the savings product default applies when the request does not override it")
                .isEqualByComparingTo("1000.00");

        // The opening deposit is a ledger movement, so the account has a complete history from the start.
        assertThat(statementService.recentEntries(opened.getId(), 0, 10)).hasSize(1);
    }

    @Test
    @DisplayName("An account holding a balance cannot be closed")
    void refusesToCloseAnAccountWithABalance() {
        Account account = AccountTestFixtures.savings(accountRepository, "500.00");

        assertThatThrownBy(() -> accountService.closeAccount(account.getId(), "customer request"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("still holds");

        assertThat(accountRepository.findById(account.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("An emptied account closes, and the closure is recorded in change history")
    void closesAnEmptyAccountAndRecordsIt() {
        Account account = AccountTestFixtures.savings(accountRepository, "0.00");

        accountService.closeAccount(account.getId(), "customer request");

        assertThat(accountRepository.findById(account.getId()).orElseThrow().getStatus())
                .isEqualTo(AccountStatus.CLOSED);
        assertThat(accountService.getChangeHistory(account.getId()))
                .singleElement()
                .satisfies(change -> {
                    assertThat(change.getFieldName()).isEqualTo("status");
                    assertThat(change.getNewValue()).isEqualTo("CLOSED");
                    assertThat(change.getReason()).isEqualTo("customer request");
                });
    }

    @Test
    @DisplayName("Every limit change is written to the append-only change history")
    void recordsLimitChanges() {
        Account account = AccountTestFixtures.savings(accountRepository, "1000.00");

        AccountLimitsRequest limits = new AccountLimitsRequest();
        limits.setDailyTransferLimit(new BigDecimal("500000.00"));
        limits.setOverdraftAllowed(true);
        limits.setOverdraftLimit(new BigDecimal("25000.00"));
        limits.setReason("relationship upgrade approved by the branch manager");

        AccountResponse updated = accountService.updateLimits(account.getId(), limits);

        assertThat(updated.getDailyTransferLimit()).isEqualByComparingTo("500000.00");
        assertThat(updated.isOverdraftAllowed()).isTrue();
        assertThat(accountService.getChangeHistory(account.getId()))
                .hasSize(3)
                .extracting(change -> change.getFieldName())
                .containsExactlyInAnyOrder("dailyTransferLimit", "overdraftAllowed", "overdraftLimit");
    }

    @Test
    @DisplayName("Freezing an account is recorded and immediately blocks movement")
    void freezesAnAccount() {
        Account account = AccountTestFixtures.savings(accountRepository, "1000.00");

        AccountStatusRequest freeze = new AccountStatusRequest();
        freeze.setStatus(AccountStatus.FROZEN);
        freeze.setReason("compliance hold");
        accountService.updateStatus(account.getId(), freeze);

        assertThatThrownBy(() -> moneyMovementService.debit(MoneyMovementRequest.builder()
                .accountId(account.getId())
                .amount(new BigDecimal("10.00"))
                .currency("INR")
                .movementType("WITHDRAWAL")
                .reference("TXN-AFTER-FREEZE")
                .operationId("freeze-test")
                .initiatedBy("test")
                .build(), "freeze-key"))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    @DisplayName("A statement reports opening and closing balances that match the ledger")
    void producesAStatementConsistentWithTheLedger() {
        Account account = AccountTestFixtures.savings(accountRepository, "0.00");

        post(account, "1000.00", "TXN-S1", "DEPOSIT");
        post(account, "250.00", "TXN-S2", "DEPOSIT");

        StatementResponse statement = statementService.generate(account.getId(),
                LocalDate.now().minusDays(1), LocalDate.now());

        assertThat(statement.getOpeningBalance()).isEqualByComparingTo("0.00");
        assertThat(statement.getClosingBalance()).isEqualByComparingTo("1250.00");
        assertThat(statement.getTotalCredits()).isEqualByComparingTo("1250.00");
        assertThat(statement.getTotalDebits()).isEqualByComparingTo("0.00");
        assertThat(statement.getEntryCount()).isEqualTo(2);
        assertThat(statement.getMaskedAccountNumber()).startsWith("XXXX");

        String csv = statementService.toCsv(statement);
        assertThat(csv).contains("Opening Balance,0.00").contains("Closing Balance,1250.00")
                .contains("TXN-S1").contains("TXN-S2");
    }

    @Test
    @DisplayName("A statement cannot span an unbounded date range")
    void refusesAnOversizedStatementRange() {
        Account account = AccountTestFixtures.savings(accountRepository, "0.00");

        assertThatThrownBy(() -> statementService.generate(account.getId(),
                LocalDate.now().minusYears(5), LocalDate.now()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("may not span more than");
    }

    private void post(Account account, String amount, String reference, String type) {
        moneyMovementService.credit(MoneyMovementRequest.builder()
                .accountId(account.getId())
                .amount(new BigDecimal(amount))
                .currency("INR")
                .movementType(type)
                .reference(reference)
                .operationId(reference)
                .initiatedBy("test")
                .build(), reference);
    }

    private AccountRequest request(BigDecimal initialDeposit) {
        AccountRequest request = new AccountRequest();
        request.setCustomerId(1L);
        request.setAccountType(AccountType.SAVINGS);
        request.setCurrency("INR");
        request.setInitialDeposit(initialDeposit);
        return request;
    }

    private CustomerProfile profile(boolean kycVerified, boolean active) {
        CustomerProfile profile = new CustomerProfile();
        profile.setCustomerId(1L);
        profile.setCustomerNumber("CUST0000000001");
        profile.setFullName("Test Customer");
        profile.setKycStatus(kycVerified ? "VERIFIED" : "PENDING");
        profile.setKycVerified(kycVerified);
        profile.setStatus(active ? "ACTIVE" : "CLOSED");
        profile.setActive(active);
        return profile;
    }
}
