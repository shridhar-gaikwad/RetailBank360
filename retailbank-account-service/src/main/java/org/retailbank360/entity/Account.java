package org.retailbank360.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.retailbank360.constants.AccountStatus;
import org.retailbank360.constants.AccountType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "accounts")
@Getter
@Setter
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String accountNumber;

    // Customer relationship
    private Long customerId;

    @NotNull(message = "Account type must not be null")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountType accountType;

    @NotNull(message = "Balance must not be null")
    @DecimalMin(value = "0.00", message = "Balance must not be negative")
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @NotNull(message = "Status must not be null")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountStatus status = AccountStatus.ACTIVE;

    @NotBlank(message = "Currency must not be blank")
    @Size(min = 3, max = 3, message = "Currency must be a 3-letter code")
    @Column(nullable = false, length = 3)
    private String currency = "INR";

    @NotNull(message = "Opened date must not be null")
    @Column(nullable = false, updatable = false)
    private LocalDateTime openedAt;

    @Column
    private LocalDateTime closedAt;

    @NotNull(message = "Created date must not be null")
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @NotNull(message = "Updated date must not be null")
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
