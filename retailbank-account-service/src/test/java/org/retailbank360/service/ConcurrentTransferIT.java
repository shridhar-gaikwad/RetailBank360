package org.retailbank360.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.retailbank360.client.CustomerServiceClient;
import org.retailbank360.common.dto.MoneyMovementRequest;
import org.retailbank360.common.dto.TransferInstruction;
import org.retailbank360.common.exception.InsufficientFundsException;
import org.retailbank360.constants.LedgerDirection;
import org.retailbank360.entity.Account;
import org.retailbank360.entity.LedgerEntry;
import org.retailbank360.repository.AccountRepository;
import org.retailbank360.repository.LedgerEntryRepository;
import org.retailbank360.support.AccountTestFixtures;
import org.retailbank360.support.DatabaseCleaner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance test the requirement asks for by name: <em>concurrent transfer attempts on the same
 * account must serialise correctly, and the final balances must be consistent.</em>
 *
 * <p>Each test hammers the same account from many threads at once and then checks three things:</p>
 * <ul>
 *   <li>the arithmetic is exactly right, with no lost update;</li>
 *   <li>money is conserved - the pair of balances still sums to what it started with;</li>
 *   <li>the ledger agrees with the balances, entry for entry.</li>
 * </ul>
 *
 * <p>A lost update would show up as a final balance that is too high, because two threads read the
 * same starting figure and one overwrote the other. That is precisely what the resource lock, the
 * {@code SELECT ... FOR UPDATE} row lock and the {@code @Version} column exist to prevent.</p>
 */
@SpringBootTest
class ConcurrentTransferIT {

    /** Feign is not exercised here; the money path never calls customer-service. */
    @MockitoBean
    private CustomerServiceClient customerServiceClient;

    @Autowired
    private MoneyMovementService moneyMovementService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerEntryRepository ledgerRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbcTemplate);
    }

    @Test
    @DisplayName("50 concurrent transfers out of one account leave exactly the right balance")
    void concurrentTransfersFromOneAccountAreSerialised() throws InterruptedException {
        Account source = AccountTestFixtures.savings(accountRepository, "10000.00");
        Account target = AccountTestFixtures.savings(accountRepository, "0.00");

        int transfers = 50;
        BigDecimal amount = new BigDecimal("100.00");
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        runConcurrently(transfers, index -> {
            try {
                moneyMovementService.transfer(TransferInstruction.builder()
                        .fromAccountId(source.getId())
                        .toAccountId(target.getId())
                        .amount(amount)
                        .currency("INR")
                        .reference("TXN-CONCURRENT-" + index)
                        .operationId("concurrency-test")
                        .initiatedBy("test")
                        .build(), "idem-" + index);
                succeeded.incrementAndGet();
            } catch (RuntimeException e) {
                failed.incrementAndGet();
            }
        });

        assertThat(succeeded.get()).as("every transfer had funds available").isEqualTo(transfers);
        assertThat(failed.get()).isZero();

        Account finalSource = accountRepository.findById(source.getId()).orElseThrow();
        Account finalTarget = accountRepository.findById(target.getId()).orElseThrow();

        assertThat(finalSource.getBalance())
                .as("no lost updates: 10000 minus 50 transfers of 100")
                .isEqualByComparingTo("5000.00");
        assertThat(finalTarget.getBalance()).isEqualByComparingTo("5000.00");
        assertThat(finalSource.getBalance().add(finalTarget.getBalance()))
                .as("money is conserved across the pair")
                .isEqualByComparingTo("10000.00");

        assertThat(ledgerRepository.count())
                .as("one debit and one credit per transfer, and not one more")
                .isEqualTo(transfers * 2L);
        assertLedgerAgreesWithBalance(source.getId(), new BigDecimal("10000.00"), finalSource.getBalance());
        assertLedgerAgreesWithBalance(target.getId(), BigDecimal.ZERO, finalTarget.getBalance());
    }

    @Test
    @DisplayName("Concurrent transfers in both directions do not deadlock")
    void oppositeDirectionTransfersDoNotDeadlock() throws InterruptedException {
        Account left = AccountTestFixtures.savings(accountRepository, "10000.00");
        Account right = AccountTestFixtures.savings(accountRepository, "10000.00");

        int pairs = 30;
        BigDecimal amount = new BigDecimal("50.00");
        AtomicInteger completed = new AtomicInteger();

        // A-to-B and B-to-A running at once is the classic deadlock shape. Both sides take their
        // locks in sorted key order, so the cycle can never form.
        runConcurrently(pairs * 2, index -> {
            boolean leftToRight = index % 2 == 0;
            moneyMovementService.transfer(TransferInstruction.builder()
                    .fromAccountId(leftToRight ? left.getId() : right.getId())
                    .toAccountId(leftToRight ? right.getId() : left.getId())
                    .amount(amount)
                    .currency("INR")
                    .reference("TXN-BIDIR-" + index)
                    .operationId("deadlock-test")
                    .initiatedBy("test")
                    .build(), "idem-bidir-" + index);
            completed.incrementAndGet();
        });

        assertThat(completed.get()).as("no transfer was lost to a deadlock").isEqualTo(pairs * 2);

        BigDecimal finalLeft = accountRepository.findById(left.getId()).orElseThrow().getBalance();
        BigDecimal finalRight = accountRepository.findById(right.getId()).orElseThrow().getBalance();

        assertThat(finalLeft.add(finalRight)).isEqualByComparingTo("20000.00");
        assertThat(finalLeft).isEqualByComparingTo("10000.00");
        assertThat(finalRight).isEqualByComparingTo("10000.00");
    }

    @Test
    @DisplayName("Concurrent withdrawals can never overdraw the account")
    void concurrentWithdrawalsNeverGoNegative() throws InterruptedException {
        Account account = AccountTestFixtures.savings(accountRepository, "1000.00");

        // Twenty threads each try to take 100 out of an account holding only 1000: at most ten can
        // succeed, and the balance must land on exactly zero rather than below it.
        int attempts = 20;
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        runConcurrently(attempts, index -> {
            try {
                moneyMovementService.debit(MoneyMovementRequest.builder()
                        .accountId(account.getId())
                        .amount(new BigDecimal("100.00"))
                        .currency("INR")
                        .movementType("WITHDRAWAL")
                        .reference("TXN-RACE-" + index)
                        .operationId("overdraw-test")
                        .initiatedBy("test")
                        .build(), "idem-race-" + index);
                succeeded.incrementAndGet();
            } catch (InsufficientFundsException e) {
                rejected.incrementAndGet();
            }
        });

        assertThat(succeeded.get()).isEqualTo(10);
        assertThat(rejected.get()).isEqualTo(10);

        BigDecimal finalBalance = accountRepository.findById(account.getId()).orElseThrow().getBalance();
        assertThat(finalBalance).isEqualByComparingTo("0.00");
        assertThat(finalBalance.signum()).as("a non-overdraft account never goes negative").isNotNegative();
        assertThat(ledgerRepository.count()).isEqualTo(10);
    }

    @Test
    @DisplayName("Replaying the same idempotency key concurrently posts the transfer only once")
    void concurrentReplaysOfOneKeyPostOnce() throws InterruptedException {
        Account source = AccountTestFixtures.savings(accountRepository, "1000.00");
        Account target = AccountTestFixtures.savings(accountRepository, "0.00");

        int replays = 10;
        AtomicInteger accepted = new AtomicInteger();

        runConcurrently(replays, index -> {
            try {
                moneyMovementService.transfer(TransferInstruction.builder()
                        .fromAccountId(source.getId())
                        .toAccountId(target.getId())
                        .amount(new BigDecimal("250.00"))
                        .currency("INR")
                        .reference("TXN-IDEMPOTENT")
                        .operationId("idempotency-test")
                        .initiatedBy("test")
                        .build(), "the-same-key");
                accepted.incrementAndGet();
            } catch (RuntimeException e) {
                // A concurrent replay is told the original is still in flight; that is the contract.
            }
        });

        assertThat(accepted.get()).as("at least the original submission went through").isPositive();
        assertThat(accountRepository.findById(source.getId()).orElseThrow().getBalance())
                .as("the money moved exactly once no matter how many replays arrived")
                .isEqualByComparingTo("750.00");
        assertThat(accountRepository.findById(target.getId()).orElseThrow().getBalance())
                .isEqualByComparingTo("250.00");
        assertThat(ledgerRepository.count()).isEqualTo(2);
    }

    /**
     * Walks the ledger for an account and checks that every {@code balanceAfter} follows from the one
     * before it, ending on the balance the account actually holds.
     *
     * <p>This is the strongest statement the test can make: not merely that the total is right, but
     * that every individual posting saw a consistent view of the balance.</p>
     */
    private void assertLedgerAgreesWithBalance(Long accountId, BigDecimal opening, BigDecimal closing) {
        List<LedgerEntry> entries = ledgerRepository
                .findStatementEntries(accountId,
                        java.time.LocalDateTime.now().minusHours(1),
                        java.time.LocalDateTime.now().plusHours(1));

        BigDecimal running = opening;
        for (LedgerEntry entry : entries) {
            running = entry.getDirection() == LedgerDirection.CREDIT
                    ? running.add(entry.getAmount())
                    : running.subtract(entry.getAmount());
            assertThat(entry.getBalanceAfter())
                    .as("entry %s records the balance that actually followed it", entry.getEntryRef())
                    .isEqualByComparingTo(running);
        }
        assertThat(running).isEqualByComparingTo(closing);
    }

    /** Fires {@code count} tasks at the same instant and waits for all of them. */
    private void runConcurrently(int count, java.util.function.IntConsumer task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(count, 25));
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(count);

        try {
            for (int i = 0; i < count; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        startGate.await();
                        task.accept(index);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startGate.countDown();
            assertThat(finished.await(120, TimeUnit.SECONDS))
                    .as("all concurrent operations completed within the timeout")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
    }
}
