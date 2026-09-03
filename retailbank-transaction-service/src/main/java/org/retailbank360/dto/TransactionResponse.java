package org.retailbank360.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TransactionResponse {

    private String transactionId;

    private Double amount;

    private String status;

    private String transactionType;
}
