package org.retailbank360.dto;

import lombok.Builder;
import lombok.Data;
import org.retailbank360.entity.AccountChangeHistory;

import java.time.LocalDateTime;

/** One row of the account change history, as shown in the admin audit view. */
@Data
@Builder
public class AccountChangeHistoryResponse {

    private String fieldName;

    private String oldValue;

    private String newValue;

    private String changedBy;

    private String reason;

    private LocalDateTime changedAt;

    public static AccountChangeHistoryResponse from(AccountChangeHistory history) {
        return AccountChangeHistoryResponse.builder()
                .fieldName(history.getFieldName())
                .oldValue(history.getOldValue())
                .newValue(history.getNewValue())
                .changedBy(history.getChangedBy())
                .reason(history.getReason())
                .changedAt(history.getChangedAt())
                .build();
    }
}
