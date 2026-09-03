package org.retailbank360.repository;

import org.retailbank360.entity.Customer;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class CustomerRepositoryImpl implements CustomerRepository {

    private final List<Customer> customers = new ArrayList<>();

    @Override
    public Customer save(Customer customer) {
        customers.add(customer);
        return customer;
    }

    @Override
    public List<Customer> findAll() {
        return new ArrayList<>(customers);
    }

    @Override
    public Optional<Customer> findById(Long customerId) {
        return customers.stream()
                .filter(customer -> customer.getId().equals(customerId))
                .findFirst();
    }

    @Override
    public Customer update(Customer customer) {
        Optional<Customer> existingCustomer = findById(customer.getId());
        if (existingCustomer.isPresent()) {
            customers.remove(existingCustomer.get());
            BeanUtils.copyProperties(customer, existingCustomer.get());
            customers.add(existingCustomer.get());
            return existingCustomer.get();
        } else {
            throw new RuntimeException("Customer not found with id: " + customer.getId());
        }
    }

    @Override
    public void deleteById(Long customerId) {
        customers.removeIf(customer -> customer.getId().equals(customerId));
    }

    @Override
    public Optional<Customer> findByMobileNumber(String mobileNumber) {
        return customers.stream()
                .filter(customer -> customer.getPhone().equals(mobileNumber))
                .findFirst();
    }

    public Optional<Customer> findByEmailId(String emailId) {
        return customers.stream()
                .filter(customer -> customer.getEmail().equals(emailId))
                .findFirst();
    }


    public Optional<Customer> findByName(String name) {
        return customers.stream()
                .filter(customer -> customer.getFirstName().equals(name))
                .findFirst();
    }

}
