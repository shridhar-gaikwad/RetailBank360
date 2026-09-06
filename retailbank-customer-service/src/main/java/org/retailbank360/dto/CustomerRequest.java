package org.retailbank360.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.retailbank360.constants.EmploymentType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Create / update payload for a customer.
 *
 * <p>A dedicated request type rather than the entity, so a caller cannot set {@code kycStatus},
 * {@code status}, {@code version} or the audit timestamps from outside - the exact fields a mass
 * assignment attack would target.</p>
 */
@Data
public class CustomerRequest {

    /** Optional; generated when omitted. */
    @Size(max = 30, message = "Customer number must not exceed 30 characters")
    private String customerNumber;

    @NotBlank(message = "First name must not be blank")
    @Size(max = 100, message = "First name must not exceed 100 characters")
    private String firstName;

    @Size(max = 100, message = "Middle name must not exceed 100 characters")
    private String middleName;

    @NotBlank(message = "Last name must not be blank")
    @Size(max = 100, message = "Last name must not exceed 100 characters")
    private String lastName;

    @NotBlank(message = "Email must not be blank")
    @Email(message = "Email must be valid")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    @NotBlank(message = "Phone must not be blank")
    @Pattern(regexp = "^[+]?[0-9]{10,15}$", message = "Phone must be 10 to 15 digits, optionally prefixed with +")
    private String phone;

    @NotBlank(message = "Customer address must not be blank")
    @Size(max = 255, message = "Customer address must not exceed 255 characters")
    private String customerAddress;

    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;

    @Pattern(regexp = "^[A-Z]{5}[0-9]{4}[A-Z]$", message = "PAN must match the format ABCDE1234F")
    private String panNumber;

    @Pattern(regexp = "^[0-9]{12}$", message = "Aadhaar must be exactly 12 digits")
    private String aadhaarNumber;

    @DecimalMin(value = "0.00", message = "Annual income must not be negative")
    private BigDecimal annualIncome;

    @Min(value = 300, message = "Credit score must be at least 300")
    @Max(value = 900, message = "Credit score must not exceed 900")
    private Integer creditScore;

    private EmploymentType employmentType;
}
