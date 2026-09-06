package org.retailbank360.support;

import org.retailbank360.constants.AccountStatus;
import org.retailbank360.constants.AccountType;
import org.retailbank360.entity.Account;
import org.retailbank360.repository.AccountRepository;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds accounts straight through the repository.
 *
 * <p>The money tests are about balances and concurrency, so they deliberately bypass the account
 * opening flow and its KYC call to customer-service. The opening flow has its own test.</p>
 */
public final class AccountTestFixtures {

    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);

    private AccountTestFixtures() {
    }

    /** Active savings account with no minimum balance, so a test controls the whole balance. */
    public static Account savings(AccountRepository repository, String balance) {
        return repository.save(build(balance, AccountType.SAVINGS, BigDecimal.ZERO));
    }

    /** Savings account with a minimum-balance floor, for the business-rule tests. */
    public static Account savingsWithMinimum(AccountRepository repository, String balance, String minimum) {
        return repository.save(build(balance, AccountType.SAVINGS, new BigDecimal(minimum)));
    }

    /** Current account with an agreed overdraft. */
    public static Account overdraft(AccountRepository repository, String balance, String overdraftLimit) {
        Account account = build(balance, AccountType.CURRENT, BigDecimal.ZERO);
        account.setOverdraftAllowed(true);
        account.setOverdraftLimit(new BigDecimal(overdraftLimit));
        return repository.save(account);
    }

    private static Account build(String balance, AccountType type, BigDecimal minimumBalance) {
        Account account = new Account();
        account.setAccountNumber(String.format("%012d", SEQUENCE.getAndIncrement()));
        account.setCustomerId(1L);
        account.setAccountType(type);
        account.setBalance(new BigDecimal(balance).setScale(2));
        account.setStatus(AccountStatus.ACTIVE);
        account.setCurrency("INR");
        account.setMinimumBalance(minimumBalance.setScale(2));
        account.setOverdraftAllowed(false);
        account.setOverdraftLimit(BigDecimal.ZERO.setScale(2));
        account.setDailyTransferLimit(new BigDecimal("100000000.00"));
        account.setDailyTransferredAmount(BigDecimal.ZERO.setScale(2));
        return account;
    }
}
