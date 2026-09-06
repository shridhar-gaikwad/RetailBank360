package org.retailbank360.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.DuplicateResourceException;
import org.retailbank360.common.exception.ResourceNotFoundException;
import org.retailbank360.constants.CustomerStatus;
import org.retailbank360.constants.EmploymentType;
import org.retailbank360.constants.KycStatus;
import org.retailbank360.dto.CustomerRequest;
import org.retailbank360.dto.CustomerResponse;
import org.retailbank360.dto.KycDecisionRequest;
import org.retailbank360.repository.CustomerRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Customer master data: PII encryption at rest, masking on the way out, blind-index lookups and the
 * KYC lifecycle that gates account opening.
 */
@SpringBootTest
class CustomerServiceIT {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        customerRepository.deleteAll();
    }

    @Test
    @DisplayName("A customer is created unverified, and identity data is masked in the response")
    void createsACustomerWithMaskedIdentity() {
        CustomerResponse created = customerService.createCustomer(request("Asha", "Rao"));

        assertThat(created.getKycStatus())
                .as("KYC is a separate, audited decision - never granted at creation")
                .isEqualTo(KycStatus.PENDING);
        assertThat(created.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(created.getCustomerNumber()).startsWith("CUST");
        assertThat(created.getFullName()).isEqualTo("Asha Rao");
        assertThat(created.getMaskedEmail()).contains("***@");
        assertThat(created.getMaskedPan()).startsWith("XXXXXX");
        assertThat(created.getMaskedPhone()).startsWith("XXXXXX");
    }

    @Test
    @DisplayName("PII is unreadable in the database but readable through the entity")
    void encryptsPiiAtRest() {
        CustomerResponse created = customerService.createCustomer(request("Bala", "Iyer"));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select email, phone, pan_number, aadhaar_number, customer_address, first_name"
                        + " from customers where id = ?", created.getId());

        // A database dump exposes ciphertext, not identities.
        assertThat(String.valueOf(row.get("email"))).startsWith("enc:v1:").doesNotContain("@");
        assertThat(String.valueOf(row.get("phone"))).startsWith("enc:v1:");
        assertThat(String.valueOf(row.get("pan_number"))).startsWith("enc:v1:");
        assertThat(String.valueOf(row.get("aadhaar_number"))).startsWith("enc:v1:");
        assertThat(String.valueOf(row.get("customer_address"))).startsWith("enc:v1:");

        // Names stay readable on purpose: they appear on statements and are searched by prefix.
        assertThat(String.valueOf(row.get("first_name"))).isEqualTo("Bala");

        // The application still sees the plain values, because the converter decrypts on read.
        assertThat(customerRepository.findById(created.getId()).orElseThrow().getEmail())
                .isEqualTo("bala.iyer@example.com");
    }

    @Test
    @DisplayName("An encrypted column can still be looked up by exact value, via its blind index")
    void looksUpAnEncryptedColumn() {
        CustomerRequest submitted = request("Chitra", "Nair");
        CustomerResponse created = customerService.createCustomer(submitted);

        assertThat(customerService.getCustomerByEmail(submitted.getEmail()).getId())
                .isEqualTo(created.getId());
        assertThat(customerService.getCustomerByMobileNumber(submitted.getPhone()).getId())
                .isEqualTo(created.getId());

        assertThatThrownBy(() -> customerService.getCustomerByEmail("nobody@example.com"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("Email and phone remain unique even though they are stored encrypted")
    void enforcesUniquenessOnEncryptedColumns() {
        CustomerRequest first = request("Deepa", "Shah");
        customerService.createCustomer(first);

        CustomerRequest duplicateEmail = request("Other", "Person");
        duplicateEmail.setEmail(first.getEmail());

        // GCM gives a different ciphertext every time, so the uniqueness lives on the blind index.
        assertThatThrownBy(() -> customerService.createCustomer(duplicateEmail))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("email");
    }

    @Test
    @DisplayName("KYC can be verified once the documents are on file")
    void verifiesKyc() {
        CustomerResponse created = customerService.createCustomer(request("Esha", "Menon"));

        KycDecisionRequest decision = new KycDecisionRequest();
        decision.setRemarks("PAN and address proof checked at the branch");
        CustomerResponse verified = customerService.verifyKyc(created.getId(), decision);

        assertThat(verified.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        assertThat(verified.getKycVerifiedAt()).isNotNull();
        assertThat(verified.getKycVerifiedBy()).isNotBlank();
        assertThat(verified.getKycRemarks()).isEqualTo(decision.getRemarks());
    }

    @Test
    @DisplayName("KYC cannot be verified without the required documents")
    void refusesKycWithoutDocuments() {
        CustomerRequest incomplete = request("Farah", "Khan");
        incomplete.setPanNumber(null);
        CustomerResponse created = customerService.createCustomer(incomplete);

        KycDecisionRequest decision = new KycDecisionRequest();
        decision.setRemarks("attempting to approve without a PAN");

        assertThatThrownBy(() -> customerService.verifyKyc(created.getId(), decision))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("PAN is required");
    }

    @Test
    @DisplayName("A rejected KYC records the reason")
    void rejectsKyc() {
        CustomerResponse created = customerService.createCustomer(request("Gopal", "Verma"));

        KycDecisionRequest decision = new KycDecisionRequest();
        decision.setRemarks("Address proof did not match the application");
        CustomerResponse rejected = customerService.rejectKyc(created.getId(), decision);

        assertThat(rejected.getKycStatus()).isEqualTo(KycStatus.REJECTED);
        assertThat(rejected.getKycRemarks()).isEqualTo(decision.getRemarks());
        assertThat(customerService.getPendingKyc()).noneSatisfy(pending ->
                assertThat(pending.getId()).isEqualTo(created.getId()));
    }

    @Test
    @DisplayName("A customer under 18 is refused")
    void refusesAnUnderageCustomer() {
        CustomerRequest minor = request("Hari", "Das");
        minor.setDateOfBirth(LocalDate.now().minusYears(15));

        assertThatThrownBy(() -> customerService.createCustomer(minor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at least 18");
    }

    @Test
    @DisplayName("Closing a customer keeps the record and blocks further edits")
    void closesRatherThanDeletes() {
        CustomerResponse created = customerService.createCustomer(request("Isha", "Patel"));

        customerService.deleteCustomerById(created.getId());

        // Accounts, ledger entries and loans reference this customer, so the row must survive.
        assertThat(customerRepository.findById(created.getId())).isPresent();
        assertThat(customerService.getCustomerById(created.getId()).getStatus())
                .isEqualTo(CustomerStatus.CLOSED);
        assertThatThrownBy(() -> customerService.updateCustomer(created.getId(), request("Isha", "Patel")))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    @DisplayName("The internal profile carries eligibility inputs and no contact details")
    void exposesAMinimalInternalProfile() {
        CustomerResponse created = customerService.createCustomer(request("Jaya", "Roy"));
        KycDecisionRequest decision = new KycDecisionRequest();
        decision.setRemarks("verified");
        customerService.verifyKyc(created.getId(), decision);

        var profile = customerService.getProfile(created.getId());

        assertThat(profile.isKycVerified()).isTrue();
        assertThat(profile.isActive()).isTrue();
        assertThat(profile.getCreditScore()).isEqualTo(780);
        assertThat(profile.getAnnualIncome()).isEqualByComparingTo("1200000.00");
        assertThat(profile.getFullName()).isEqualTo("Jaya Roy");
    }

    @Test
    @DisplayName("Customers can be searched by partial name")
    void searchesByName() {
        customerService.createCustomer(request("Kiran", "Kulkarni"));
        customerService.createCustomer(request("Lata", "Kulkarni"));

        assertThat(customerService.searchCustomersByName("kulkarni")).hasSize(2);
        assertThat(customerService.searchCustomersByName("kir")).hasSize(1);
        assertThat(customerService.searchCustomersByName("nobody")).isEmpty();
    }

    private CustomerRequest request(String firstName, String lastName) {
        int sequence = SEQUENCE.getAndIncrement();
        CustomerRequest request = new CustomerRequest();
        request.setFirstName(firstName);
        request.setLastName(lastName);
        request.setEmail((firstName + "." + lastName + "@example.com").toLowerCase());
        request.setPhone(String.format("98765%05d", sequence));
        request.setCustomerAddress("12 Residency Road, Bengaluru 560025");
        request.setDateOfBirth(LocalDate.now().minusYears(32));
        request.setPanNumber("ABCDE" + String.format("%04d", sequence) + "F");
        request.setAadhaarNumber(String.format("%012d", 900000000000L + sequence));
        request.setAnnualIncome(new BigDecimal("1200000.00"));
        request.setCreditScore(780);
        request.setEmploymentType(EmploymentType.SALARIED);
        return request;
    }
}
