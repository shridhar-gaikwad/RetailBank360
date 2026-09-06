package org.retailbank360.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Account statement for a date range.
 *
 * <p>The opening balance is taken from the closing balance of the last entry before the window, not
 * recomputed, so the statement is consistent with the ledger by construction.</p>
 */
@Data
@Builder
public class StatementResponse {

    private Long accountId;

    private String maskedAccountNumber;

    private String accountHolderRef;

    private String currency;

    private LocalDate fromDate;

    private LocalDate toDate;

    private BigDecimal openingBalance;

    private BigDecimal closingBalance;

    private BigDecimal totalCredits;

    private BigDecimal totalDebits;

    private int entryCount;

    private LocalDateTime generatedAt;

    private String generatedBy;

    private List<LedgerEntryResponse> entries;
}
