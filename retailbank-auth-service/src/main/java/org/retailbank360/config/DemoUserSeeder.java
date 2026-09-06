package org.retailbank360.config;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.common.constants.Roles;
import org.retailbank360.entity.UserAccount;
import org.retailbank360.repository.UserAccountRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates one login per role on first start, so the POC is usable immediately.
 *
 * <p>Switched on by {@code retailbank.seed-demo-data} and off by default outside the local profile,
 * because seeding well known credentials into a real environment would be a serious weakness.
 * Existing usernames are left untouched, so a restart never resets a changed password.</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "retailbank.seed-demo-data", havingValue = "true")
public class DemoUserSeeder implements CommandLineRunner {

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DemoUserSeeder(UserAccountRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        seed("admin", "Admin@123", Roles.ADMIN, null, "admin@retailbank360.local");
        seed("teller1", "Teller@123", Roles.TELLER, null, "teller1@retailbank360.local");
        seed("officer1", "Officer@123", Roles.LOAN_OFFICER, null, "officer1@retailbank360.local");
        seed("customer1", "Customer@123", Roles.CUSTOMER, 1L, "customer1@retailbank360.local");
        seed("customer2", "Customer@123", Roles.CUSTOMER, 2L, "customer2@retailbank360.local");
    }

    private void seed(String username, String password, String role, Long customerId, String email) {
        if (userRepository.existsByUsername(username)) {
            return;
        }
        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(role);
        user.setCustomerId(customerId);
        user.setEmail(email);
        user.setEnabled(true);
        userRepository.save(user);
        log.info("Seeded demo login '{}' with role {}", username, role);
    }
}
