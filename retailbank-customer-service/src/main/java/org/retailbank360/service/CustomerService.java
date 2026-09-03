package org.retailbank360.service;

import org.retailbank360.entity.Customer;

import java.util.List;
import java.util.Optional;

public interface CustomerService {

    Customer createCustomer(Customer customer);

    List<Customer> getAllCustomers();

    Optional<Customer> getCustomerById(Long customerId);

    Customer updateCustomer(Customer request);

    void deleteCustomerById(Long customerId);
}
