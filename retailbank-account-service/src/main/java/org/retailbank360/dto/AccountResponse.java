package org.retailbank360.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import org.retailbank360.common.security.SecurityUtils;
import org.retailbank360.common.util.MaskingUtil;
import org.retailbank360.constants.AccountStatus;
import org.retailbank360.constants.AccountType;
import org.retailbank360.entity.Account;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Account as returned by the API.
 *
 * <p>The account number is masked to the last four digits, which is what the requirement asks for.
 * Staff and internal service callers additionally receive the full number, because a teller
 * genuinely needs it to service a walk-in customer; a {@code CUSTOMER} principal never does.</p>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccountResponse {

    private Long id;

    /** Always present, always masked, e.g. {@code XXXXXXXX4821}. */
    private String maskedAccountNumber;

    /** Full number, present only for staff and service principals. */
    private String accountNumber;

    private Long customerId;

    private AccountType accountType;

    private BigDecimal balance;

    /** Balance less the minimum-balance floor, plus any overdraft head-room. */
    private BigDecimal availableBalance;

    private String currency;

    private AccountStatus status;

    private BigDecimal minimumBalance;

    private boolean overdraftAllowed;

    private BigDecimal overdraftLimit;

    private BigDecimal dailyTransferLimit;

    private BigDecimal transferredToday;

    private BigDecimal remainingDailyLimit;

    private LocalDateTime openedAt;

    private LocalDateTime closedAt;

    private LocalDateTime updatedAt;

    /** Echoed so a client can send it back on an update and get optimistic-lock protection. */
    private Long version;

    public static AccountResponse from(Account account) {
        boolean revealFullNumber = !SecurityUtils.isCustomerPrincipal();
        BigDecimal transferredToday = account.getTransferredToday();

        return AccountResponse.builder()
                .id(account.getId())
                .maskedAccountNumber(MaskingUtil.maskAccountNumber(account.getAccountNumber()))
                .accountNumber(revealFullNumber ? account.getAccountNumber() : null)
                .customerId(account.getCustomerId())
                .accountType(account.getAccountType())
                .balance(account.getBalance())
                .availableBalance(account.getAvailableBalance())
                .currency(account.getCurrency())
                .status(account.getStatus())
                .minimumBalance(account.getMinimumBalance())
                .overdraftAllowed(account.isOverdraftAllowed())
                .overdraftLimit(account.getOverdraftLimit())
                .dailyTransferLimit(account.getDailyTransferLimit())
                .transferredToday(transferredToday)
                .remainingDailyLimit(account.getDailyTransferLimit().subtract(transferredToday).max(BigDecimal.ZERO))
                .openedAt(account.getOpenedAt())
                .closedAt(account.getClosedAt())
                .updatedAt(account.getUpdatedAt())
                .version(account.getVersion())
                .build();
    }
}
