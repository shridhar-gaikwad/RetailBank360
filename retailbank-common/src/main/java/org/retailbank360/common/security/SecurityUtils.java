package org.retailbank360.common.security;

import org.retailbank360.common.constants.Roles;
import org.retailbank360.common.exception.UnauthorizedOperationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * Convenience accessors over the Spring {@code SecurityContext}.
 *
 * <p>Role checks are declared with {@code @PreAuthorize} on the controllers. This class covers the
 * second half of authorization that annotations cannot express: <em>row</em> ownership, i.e. a
 * customer may only reach their own accounts, transactions, statements and loans.</p>
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<AuthenticatedUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    public static AuthenticatedUser requireCurrentUser() {
        return currentUser().orElseThrow(
                () -> new UnauthorizedOperationException("No authenticated principal on this request"));
    }

    /** Username for audit records; falls back to {@code system} for unauthenticated internal work. */
    public static String currentUsername() {
        return currentUser().map(AuthenticatedUser::username).orElse("system");
    }

    public static boolean hasRole(String role) {
        return currentUser().map(user -> role.equals(user.role())).orElse(false);
    }

    /** True for teller, loan officer, admin and internal service principals. */
    public static boolean isStaffOrService() {
        return currentUser().map(user -> switch (user.role() == null ? "" : user.role()) {
            case Roles.TELLER, Roles.LOAN_OFFICER, Roles.ADMIN, Roles.SERVICE -> true;
            default -> false;
        }).orElse(false);
    }

    /**
     * Allows staff and service principals through, but restricts a {@code CUSTOMER} principal to
     * their own customer id.
     *
     * @throws UnauthorizedOperationException when a customer targets somebody else
     */
    public static void requireCustomerAccess(Long targetCustomerId) {
        Optional<AuthenticatedUser> current = currentUser();
        if (current.isEmpty()) {
            // Security disabled (slice tests, internal batch jobs): nothing to enforce.
            return;
        }
        AuthenticatedUser user = current.get();
        if (!user.isCustomer()) {
            return;
        }
        if (targetCustomerId == null || !targetCustomerId.equals(user.customerId())) {
            throw new UnauthorizedOperationException(
                    "Customer " + user.username() + " is not allowed to access data of customer " + targetCustomerId);
        }
    }

    /** True when the caller is a customer, i.e. responses must be masked and scoped. */
    public static boolean isCustomerPrincipal() {
        return currentUser().map(AuthenticatedUser::isCustomer).orElse(false);
    }
}
