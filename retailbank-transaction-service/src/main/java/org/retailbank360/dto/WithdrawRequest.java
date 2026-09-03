package org.retailbank360.dto;

import lombok.Data;

@Data
public class WithdrawRequest {

    private Long accountNumber;

    private Double amount;
}
