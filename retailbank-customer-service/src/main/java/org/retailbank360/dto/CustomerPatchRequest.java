package org.retailbank360.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.retailbank360.constants.EmploymentType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Partial-update payload for a customer.
 *
 * <p>Every field is optional. A {@code null} field means "leave unchanged" - it is not a request to
 * clear the column, so a value cannot be blanked through this endpoint. The format constraints below
 * are all null-tolerant and fire only when a value is actually present, which is why there is no
 * {@code @NotBlank} here as there is on {@link CustomerRequest}: {@code {"firstName":"Bob"}} must
 * pass, while {@code {"email":"bad"}} must still be rejected.</p>
 *
 * <p>{@code customerNumber}, {@code kycStatus}, {@code status}, {@code version} and the audit
 * timestamps are deliberately absent, exactly as on {@link CustomerRequest}.</p>
 */
@Data
public class CustomerPatchRequest {

    @Size(max = 100, message = "First name must not exceed 100 characters")
    private String firstName;

    @Size(max = 100, message = "Middle name must not exceed 100 characters")
    private String middleName;

    @Size(max = 100, message = "Last name must not exceed 100 characters")
    private String lastName;

    @Email(message = "Email must be valid")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    @Pattern(regexp = "^[+]?[0-9]{10,15}$", message = "Phone must be 10 to 15 digits, optionally prefixed with +")
    private String phone;

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
