package org.retailbank360.repository;

import org.retailbank360.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CustomerRepository {

    Customer save(Customer customer);

    List<Customer> findAll();

    Optional<Customer> findById(Long customerId);

    Customer update(Customer customer);

    void deleteById(Long customerId);

    Optional<Customer> findByMobileNumber(String mobileNumber);

    Optional<Customer> findByEmailId(String emailId);

    Optional<Customer> findByName(String name);
}
