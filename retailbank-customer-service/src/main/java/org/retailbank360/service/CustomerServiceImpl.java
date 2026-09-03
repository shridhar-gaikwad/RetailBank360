package org.retailbank360.service;

import org.retailbank360.entity.Customer;
import org.retailbank360.repository.CustomerRepositoryImpl;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class CustomerServiceImpl implements CustomerService{

    private final CustomerRepositoryImpl customerRepositoryImpl;

    public CustomerServiceImpl(CustomerRepositoryImpl customerRepositoryImpl) {
        this.customerRepositoryImpl = customerRepositoryImpl;
    }

    @Override
    public Customer createCustomer(Customer customer) {
        return customerRepositoryImpl.save(customer);
    }

    @Override
    public List<Customer> getAllCustomers() {
        return customerRepositoryImpl.findAll();
    }

    @Override
    public Optional<Customer> getCustomerById(Long customerId) {
        return customerRepositoryImpl.findById(customerId);
    }

    @Override
    public Customer updateCustomer(Customer request) {
        return customerRepositoryImpl.update(request);
    }

    @Override
    public void deleteCustomerById(Long customerId) {
        customerRepositoryImpl.deleteById(customerId);
    }

    public Optional<Customer> getCustomerByMobileNumber(String mobileNumber) {
        return customerRepositoryImpl.findByMobileNumber(mobileNumber);
    }

    public Optional<Customer> getCustomerByEmail(String emailId) {
        return customerRepositoryImpl.findByEmailId(emailId);
    }

    public Optional<Customer> getCustomerByName(String name) {
        return customerRepositoryImpl.findByName(name);
    }
}
