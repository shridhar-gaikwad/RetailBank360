package org.retailbank360.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.retailbank360.constants.CustomerStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Entity
@Table(name = "customers")
@Getter
@Setter
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Customer number must not be blank")
    @Size(max = 30, message = "Customer number must not exceed 30 characters")
    @Column(nullable = false, unique = true, length = 30)
    private String customerNumber;

    @NotBlank(message = "First name must not be blank")
    @Size(max = 100, message = "First name must not exceed 100 characters")
    @Column(nullable = false, length = 100)
    private String firstName;

    @Size(max = 100, message = "Middle name must not exceed 100 characters")
    @Column(length = 100)
    private String middleName;

    @NotBlank(message = "Last name must not be blank")
    @Size(max = 100, message = "Last name must not exceed 100 characters")
    @Column(nullable = false, length = 100)
    private String lastName;

    @NotBlank(message = "Email must not be blank")
    @Email(message = "Email must be valid")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Size(max = 20, message = "Phone must not exceed 20 characters")
    @Column(length = 20)
    private String phone;

    @NotBlank(message = "Customer address must not be blank")
    @Size(max = 255, message = "Customer address must not exceed 255 characters")
    @Column(nullable = false)
    private String customerAddress;

    @Column
    private LocalDate dateOfBirth;

    @NotNull(message = "Status must not be null")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CustomerStatus status = CustomerStatus.ACTIVE;

    @NotNull(message = "Created date must not be null")
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @NotNull(message = "Updated date must not be null")
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Transient public String getFullName() {
        return Stream.of(firstName, middleName, lastName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .collect(Collectors.joining(" "));
    }
}