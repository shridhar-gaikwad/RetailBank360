package org.retailbank360.dto;

import lombok.Data;

@Data
public class TransferRequest {

    private Long fromAccount;

    private Long toAccount;

    private Double amount;
}