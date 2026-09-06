package org.retailbank360.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Contact details read from customer-service. Tolerant reader: unknown fields are ignored. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CustomerContact {

    private Long customerId;

    private String fullName;

    private String email;

    private String phone;

    private boolean active;
}
