package org.retailbank360.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.retailbank360.constants.TransactionStatus;
import org.retailbank360.constants.TransactionType;

import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
@Getter
@Setter
public class Transaction {

    private String transactionId;

    private Long fromAccount;

    private Long toAccount;

    private Double amount;

    private TransactionType transactionType;

    private TransactionStatus status;

    private LocalDateTime transactionDate;

}