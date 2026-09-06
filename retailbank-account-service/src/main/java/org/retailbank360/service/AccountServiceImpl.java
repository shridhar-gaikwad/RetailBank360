package org.retailbank360.service;

import lombok.extern.slf4j.Slf4j;
import org.retailbank360.client.CustomerServiceClient;
import org.retailbank360.client.dto.CustomerProfile;
import org.retailbank360.common.audit.AuditActions;
import org.retailbank360.common.audit.AuditPublisher;
import org.retailbank360.common.dto.MoneyMovementRequest;
import org.retailbank360.common.exception.BusinessRuleViolationException;
import org.retailbank360.common.exception.ExternalServiceException;
import org.retailbank360.common.exception.ResourceNotFoundException;
import org.retailbank360.common.lock.LockRequest;
import org.retailbank360.common.lock.LockResourceTypes;
import org.retailbank360.common.lock.LockTemplate;
import org.retailbank360.common.security.SecurityUtils;
import org.retailbank360.common.util.IdGenerator;
import org.retailbank360.common.util.MoneyUtil;
import org.retailbank360.common.web.PageRequests;
import org.retailbank360.constants.AccountStatus;
import org.retailbank360.constants.AccountType;
import org.retailbank360.dto.AccountChangeHistoryResponse;
import org.retailbank360.dto.AccountLimitsRequest;
import org.retailbank360.dto.AccountRequest;
import org.retailbank360.dto.AccountResponse;
import org.retailbank360.dto.AccountStatusRequest;
import org.retailbank360.entity.Account;
import org.retailbank360.repository.AccountChangeHistoryRepository;
import org.retailbank360.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Account lifecycle: opening, lookups, limits, status and closure.
 *
 * <p>Administrative changes are wrapped in the same resource lock money movement uses, so an
 * operator cannot freeze or re-limit an account while a transfer on it is mid-flight - the two
 * operations serialise on {@code ACCOUNT:<id>} instead of racing.</p>
 */
@Slf4j
@Service
public class AccountServiceImpl implements AccountService {

    /** Maximum number of live accounts one customer may hold, a simple product rule. */
    private static final int MAX_ACTIVE_ACCOUNTS_PER_CUSTOMER = 5;

    private final AccountRepository accountRepository;
    private final AccountChangeHistoryRepository historyRepository;
    private final AccountAdminTxService adminTxService;
    private final MoneyMovementService moneyMovementService;
    private final CustomerServiceClient customerServiceClient;
    private final LockTemplate lockTemplate;
    private final AuditPublisher auditPublisher;

    public AccountServiceImpl(AccountRepository accountRepository,
                              AccountChangeHistoryRepository historyRepository,
                              AccountAdminTxService adminTxService,
                              MoneyMovementService moneyMovementService,
                              CustomerServiceClient customerServiceClient,
                              LockTemplate lockTemplate,
                              AuditPublisher auditPublisher) {
        this.accountRepository = accountRepository;
        this.historyRepository = historyRepository;
        this.adminTxService = adminTxService;
        this.moneyMovementService = moneyMovementService;
        this.customerServiceClient = customerServiceClient;
        this.lockTemplate = lockTemplate;
        this.auditPublisher = auditPublisher;
    }

    @Override
    public AccountResponse createAccount(AccountRequest request) {
        CustomerProfile profile = fetchCustomerProfile(request.getCustomerId());

        // KYC gate: the requirement is explicit that account opening validates KYC.
        if (!profile.isKycVerified()) {
            throw new BusinessRuleViolationException("KYC_NOT_VERIFIED",
                    "Customer " + request.getCustomerId() + " has KYC status " + profile.getKycStatus()
                            + ". An account can only be opened once KYC is verified.");
        }
        if (!profile.isActive()) {
            throw new BusinessRuleViolationException("CUSTOMER_NOT_ACTIVE",
                    "Customer " + request.getCustomerId() + " is " + profile.getStatus());
        }
        long liveAccounts = accountRepository.countByCustomerIdAndStatus(request.getCustomerId(),
                AccountStatus.ACTIVE);
        if (liveAccounts >= MAX_ACTIVE_ACCOUNTS_PER_CUSTOMER) {
            throw new BusinessRuleViolationException("ACCOUNT_LIMIT_REACHED",
                    "Customer " + request.getCustomerId() + " already holds "
                            + MAX_ACTIVE_ACCOUNTS_PER_CUSTOMER + " active accounts");
        }

        Account account = persistNewAccount(request);
        auditPublisher.publishSuccess(AuditActions.ACCOUNT_OPENED, "ACCOUNT", account.getId(), null);

        // The opening deposit is a real ledger movement, not a seeded balance, so the account has a
        // complete history from its very first rupee.
        if (MoneyUtil.isPositive(request.getInitialDeposit())) {
            MoneyMovementRequest deposit = MoneyMovementRequest.builder()
                    .accountId(account.getId())
                    .amount(request.getInitialDeposit())
                    .currency(account.getCurrency())
                    .movementType("DEPOSIT")
                    .reference(IdGenerator.reference("OPEN"))
                    .operationId("account-open-" + account.getId())
                    .description("Opening deposit")
                    .initiatedBy(SecurityUtils.currentUsername())
                    .build();
            moneyMovementService.credit(deposit, "open-" + account.getId());
            account = requireAccount(account.getId());
        }

        log.info("Opened {} account {} for customer {}",
                account.getAccountType(), account.getAccountNumber(), account.getCustomerId());
        return AccountResponse.from(account);
    }

    /**
     * Inserts the account row.
     *
     * <p>Not annotated with {@code @Transactional}: it performs a single {@code save}, which Spring
     * Data already runs in its own transaction. Annotating it here would be misleading anyway, since
     * it is called from within this bean and a self-invocation never reaches the proxy.</p>
     */
    private Account persistNewAccount(AccountRequest request) {
        Account account = new Account();
        account.setAccountNumber(generateAccountNumber());
        account.setCustomerId(request.getCustomerId());
        account.setAccountType(request.getAccountType());
        account.setCurrency(request.getCurrency() == null || request.getCurrency().isBlank()
                ? "INR" : request.getCurrency().toUpperCase());
        account.setBalance(BigDecimal.ZERO.setScale(MoneyUtil.SCALE));
        account.setStatus(AccountStatus.ACTIVE);
        applyProductDefaults(account, request);
        return accountRepository.save(account);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountResponse> getAllAccounts(int page, int size) {
        return accountRepository.findAll(PageRequests.of(page, size))
                .map(AccountResponse::from)
                .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse getAccountById(Long id) {
        Account account = requireAccount(id);
        SecurityUtils.requireCustomerAccess(account.getCustomerId());
        return AccountResponse.from(account);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountResponse getAccountByNumber(String accountNumber) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow(() -> new ResourceNotFoundException("No account with that number"));
        SecurityUtils.requireCustomerAccess(account.getCustomerId());
        return AccountResponse.from(account);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountResponse> getAccountsByCustomer(Long customerId) {
        SecurityUtils.requireCustomerAccess(customerId);
        return accountRepository.findByCustomerId(customerId).stream().map(AccountResponse::from).toList();
    }

    @Override
    public AccountResponse updateLimits(Long id, AccountLimitsRequest request) {
        AccountResponse response = lockTemplate.executeWithLock(
                LockRequest.of(LockResourceTypes.ACCOUNT, id, "limits-" + id),
                handle -> adminTxService.applyLimits(id, request));
        auditPublisher.publishSuccess(AuditActions.ACCOUNT_UPDATED, "ACCOUNT", id, null);
        return response;
    }

    @Override
    public AccountResponse updateStatus(Long id, AccountStatusRequest request) {
        AccountResponse response = lockTemplate.executeWithLock(
                LockRequest.of(LockResourceTypes.ACCOUNT, id, "status-" + id),
                handle -> adminTxService.applyStatus(id, request));
        auditPublisher.publishSuccess(AuditActions.ACCOUNT_STATUS_CHANGED, "ACCOUNT", id, null);
        return response;
    }

    @Override
    public void closeAccount(Long id, String reason) {
        lockTemplate.executeWithLock(
                LockRequest.of(LockResourceTypes.ACCOUNT, id, "close-" + id),
                handle -> {
                    adminTxService.close(id, reason == null ? "closed on request" : reason);
                    return null;
                });
        auditPublisher.publishSuccess(AuditActions.ACCOUNT_CLOSED, "ACCOUNT", id, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountChangeHistoryResponse> getChangeHistory(Long id) {
        requireAccount(id);
        return historyRepository.findByAccountIdOrderByChangedAtDesc(id).stream()
                .map(AccountChangeHistoryResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getAccountSummary(Long id) {
        Account account = requireAccount(id);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("accountId", account.getId());
        summary.put("customerId", account.getCustomerId());
        summary.put("currency", account.getCurrency());
        summary.put("status", account.getStatus().name());
        summary.put("accountType", account.getAccountType().name());
        return summary;
    }

    /**
     * Product rules per account type, overridable per account by an explicit request value.
     *
     * <p>A fixed deposit gets a zero daily transfer limit, which is how "no outbound transfers"
     * is expressed without a special case in the money path.</p>
     */
    private void applyProductDefaults(Account account, AccountRequest request) {
        AccountType type = request.getAccountType();
        BigDecimal defaultMinimum = switch (type) {
            case SAVINGS -> new BigDecimal("1000.00");
            case CURRENT -> new BigDecimal("5000.00");
            case SALARY, FIXED_DEPOSIT -> BigDecimal.ZERO;
        };
        BigDecimal defaultDailyLimit = switch (type) {
            case SAVINGS, SALARY -> new BigDecimal("200000.00");
            case CURRENT -> new BigDecimal("1000000.00");
            case FIXED_DEPOSIT -> BigDecimal.ZERO;
        };
        boolean defaultOverdraft = type == AccountType.CURRENT;

        account.setMinimumBalance(MoneyUtil.normalize(
                request.getMinimumBalance() != null ? request.getMinimumBalance() : defaultMinimum));
        account.setDailyTransferLimit(MoneyUtil.normalize(
                request.getDailyTransferLimit() != null ? request.getDailyTransferLimit() : defaultDailyLimit));
        account.setOverdraftAllowed(
                request.getOverdraftAllowed() != null ? request.getOverdraftAllowed() : defaultOverdraft);
        account.setOverdraftLimit(MoneyUtil.normalize(
                request.getOverdraftLimit() != null ? request.getOverdraftLimit()
                        : (defaultOverdraft ? new BigDecimal("50000.00") : BigDecimal.ZERO)));
        account.setDailyTransferredAmount(BigDecimal.ZERO.setScale(MoneyUtil.SCALE));

        if (!account.isOverdraftAllowed()) {
            account.setOverdraftLimit(BigDecimal.ZERO.setScale(MoneyUtil.SCALE));
        }
    }

    private CustomerProfile fetchCustomerProfile(Long customerId) {
        try {
            CustomerProfile profile = customerServiceClient.getProfile(customerId);
            if (profile == null || profile.getCustomerId() == null) {
                throw new ResourceNotFoundException("Customer", customerId);
            }
            return profile;
        } catch (ResourceNotFoundException e) {
            throw e;
        } catch (RuntimeException e) {
            // Failing closed is the only safe option: opening an account without a verified KYC file
            // because a downstream call timed out would defeat the control entirely.
            throw new ExternalServiceException("customer-service",
                    "unable to verify customer " + customerId, e);
        }
    }

    private String generateAccountNumber() {
        String candidate;
        do {
            candidate = IdGenerator.numeric(12);
        } while (accountRepository.existsByAccountNumber(candidate));
        return candidate;
    }

    private Account requireAccount(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
    }
}
