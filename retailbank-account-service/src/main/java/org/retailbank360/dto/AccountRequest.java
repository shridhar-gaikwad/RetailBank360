package org.retailbank360.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.retailbank360.constants.AccountType;

import java.math.BigDecimal;

/**
 * Account opening request.
 *
 * <p>Balance and status are absent on purpose: an account is opened at zero and funded through a
 * ledger movement, so there is no path that sets a balance without an accompanying entry.</p>
 */
@Data
public class AccountRequest {

    @NotNull(message = "Customer id must not be null")
    private Long customerId;

    @NotNull(message = "Account type must not be null")
    private AccountType accountType;

    @Size(min = 3, max = 3, message = "Currency must be a 3-letter code")
    private String currency;

    /** Optional opening deposit, posted as a DEPOSIT ledger entry once the account exists. */
    @DecimalMin(value = "0.00", message = "Initial deposit must not be negative")
    @Digits(integer = 17, fraction = 2, message = "Amount must have at most two decimal places")
    private BigDecimal initialDeposit;

    /** Defaults to the product rule for the account type when omitted. */
    @DecimalMin(value = "0.00", message = "Minimum balance must not be negative")
    private BigDecimal minimumBalance;

    private Boolean overdraftAllowed;

    @DecimalMin(value = "0.00", message = "Overdraft limit must not be negative")
    private BigDecimal overdraftLimit;

    @DecimalMin(value = "0.00", message = "Daily transfer limit must not be negative")
    private BigDecimal dailyTransferLimit;
}
