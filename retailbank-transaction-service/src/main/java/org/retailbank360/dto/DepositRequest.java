package org.retailbank360.dto;

import lombok.Data;

@Data
public class DepositRequest {

    private Long accountNumber;

    private Double amount;
}
