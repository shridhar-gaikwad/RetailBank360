package org.retailbank360.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.retailbank360.entity.Customer;

/**
 * Contact details for one customer, in the clear.
 *
 * <p>The one place decrypted contact data leaves this service, and deliberately narrow: it carries a
 * name and two addresses, nothing else. notification-service needs a real address to deliver to, and
 * no amount of masking substitutes for that. The endpoint is restricted to a {@code SERVICE}
 * principal, and the consumer masks the value before it reaches any log.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerContactResponse {

    private Long customerId;

    private String fullName;

    private String email;

    private String phone;

    /** A closed customer should not be contacted about product events. */
    private boolean active;

    public static CustomerContactResponse from(Customer customer) {
        return CustomerContactResponse.builder()
                .customerId(customer.getId())
                .fullName(customer.getFullName())
                .email(customer.getEmail())
                .phone(customer.getPhone())
                .active(customer.getStatus() == org.retailbank360.constants.CustomerStatus.ACTIVE)
                .build();
    }
}
