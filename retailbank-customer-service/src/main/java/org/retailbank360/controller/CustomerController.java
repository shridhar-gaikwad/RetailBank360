package org.retailbank360.controller;

import org.retailbank360.entity.Customer;
import org.retailbank360.service.CustomerServiceImpl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private final CustomerServiceImpl customerServiceImpl;

    public CustomerController(CustomerServiceImpl customerServiceImpl) {
        this.customerServiceImpl = customerServiceImpl;
    }

    @PostMapping
    public ResponseEntity<Customer> create(
            @RequestBody Customer request) {

        return ResponseEntity.ok(
                customerServiceImpl.createCustomer(request)
        );
    }

    @GetMapping("/all")
    public ResponseEntity<List<Customer>> getAllCustomers() {
        return ResponseEntity.ok(customerServiceImpl.getAllCustomers());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Customer> updateCustomer(
            @PathVariable Long id,
            @RequestBody Customer request) {
        request.setId(id);
        return ResponseEntity.ok(customerServiceImpl.updateCustomer(request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCustomerById(
            @PathVariable Long id) {
        customerServiceImpl.deleteCustomerById(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Customer> getCustomerById(
            @PathVariable Long id) {
        return ResponseEntity.ok(customerServiceImpl.getCustomerById(id).get());
    }

    @GetMapping("/mobile/{mobileNumber}")
    public ResponseEntity<Customer> getCustomerByMobileNumber(
            @PathVariable String mobileNumber) {
        return ResponseEntity.of(customerServiceImpl.getCustomerByMobileNumber(mobileNumber));
    }

    @GetMapping("/email/{email}")
    public ResponseEntity<Customer> getCustomerByEmail(
            @PathVariable String email) {
        return ResponseEntity.of(customerServiceImpl.getCustomerByEmail(email));
    }

    @GetMapping("/name/{name}")
    public ResponseEntity<Customer> getCustomerByName(
            @PathVariable String name) {
        return ResponseEntity.of(customerServiceImpl.getCustomerByName(name));
    }
}
