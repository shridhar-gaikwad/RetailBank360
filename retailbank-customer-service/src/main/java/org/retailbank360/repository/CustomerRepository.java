package org.retailbank360.repository;

import jakarta.persistence.LockModeType;
import org.retailbank360.constants.KycStatus;
import org.retailbank360.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for customers.
 *
 * <p>Replaces the earlier in-memory {@code ArrayList} implementation: with the ledger, KYC gating and
 * loan eligibility all reading this data, it has to be durable, transactional and lockable.</p>
 *
 * <p>Lookups by email, phone and PAN go through the blind-index columns, because the values
 * themselves are stored encrypted with a random IV and cannot be matched directly.</p>
 */
@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByCustomerNumber(String customerNumber);

    boolean existsByCustomerNumber(String customerNumber);

    Optional<Customer> findByEmailIndex(String emailIndex);

    Optional<Customer> findByPhoneIndex(String phoneIndex);

    Optional<Customer> findByPanIndex(String panIndex);

    boolean existsByEmailIndex(String emailIndex);

    boolean existsByPhoneIndex(String phoneIndex);

    List<Customer> findByFirstNameIgnoreCaseOrLastNameIgnoreCase(String firstName, String lastName);

    Page<Customer> findByKycStatus(KycStatus kycStatus, Pageable pageable);

    @Query("""
            select c from Customer c
             where lower(c.firstName) like lower(concat('%', :term, '%'))
                or lower(c.lastName)  like lower(concat('%', :term, '%'))
             order by c.lastName, c.firstName
            """)
    List<Customer> searchByName(@Param("term") String term);

    /**
     * Row-locked read for KYC transitions and profile edits, so two tellers cannot approve and reject
     * the same customer at the same instant.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Customer c where c.id = :id")
    Optional<Customer> findByIdForUpdate(@Param("id") Long id);

    Customer update(Customer customer);

    Optional<Customer> findByMobileNumber(String mobileNumber);
}
