package org.retailbank360.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import org.retailbank360.common.util.MaskingUtil;
import org.retailbank360.constants.LedgerDirection;
import org.retailbank360.constants.MovementType;
import org.retailbank360.entity.LedgerEntry;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One ledger line as shown on a statement. */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LedgerEntryResponse {

    private String entryRef;

    private String maskedAccountNumber;

    private LedgerDirection direction;

    private MovementType movementType;

    private BigDecimal amount;

    private String currency;

    private BigDecimal balanceAfter;

    private String reference;

    private String description;

    private Long counterpartyAccountId;

    private String reversesEntryRef;

    private LocalDateTime postedAt;

    public static LedgerEntryResponse from(LedgerEntry entry) {
        return LedgerEntryResponse.builder()
                .entryRef(entry.getEntryRef())
                .maskedAccountNumber(MaskingUtil.maskAccountNumber(entry.getAccountNumber()))
                .direction(entry.getDirection())
                .movementType(entry.getMovementType())
                .amount(entry.getAmount())
                .currency(entry.getCurrency())
                .balanceAfter(entry.getBalanceAfter())
                .reference(entry.getReference())
                .description(entry.getDescription())
                .counterpartyAccountId(entry.getCounterpartyAccountId())
                .reversesEntryRef(entry.getReversesEntryRef())
                .postedAt(entry.getPostedAt())
                .build();
    }
}
