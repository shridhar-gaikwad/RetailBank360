package org.retailbank360.common.security;

import java.io.Serializable;

/**
 * The principal placed in the Spring {@code SecurityContext} once a JWT has been verified.
 *
 * @param userId     primary key of the login record in the auth-service
 * @param username   login name
 * @param role       one of {@code org.retailbank360.common.constants.Roles}
 * @param customerId customer this login belongs to, {@code null} for staff and service principals
 */
public record AuthenticatedUser(Long userId, String username, String role, Long customerId)
        implements Serializable {

    public boolean isCustomer() {
        return org.retailbank360.common.constants.Roles.CUSTOMER.equals(role);
    }

    public boolean isService() {
        return org.retailbank360.common.constants.Roles.SERVICE.equals(role);
    }

    @Override
    public String toString() {
        return username + "(" + role + ")";
    }
}
