package org.retailbank360.service;

import org.retailbank360.dto.AccountChangeHistoryResponse;
import org.retailbank360.dto.AccountLimitsRequest;
import org.retailbank360.dto.AccountRequest;
import org.retailbank360.dto.AccountResponse;
import org.retailbank360.dto.AccountStatusRequest;

import java.util.List;
import java.util.Map;

/** Account lifecycle and administration. Money movement lives in {@link MoneyMovementService}. */
public interface AccountService {

    /** Opens an account. Rejected unless the customer exists, is active and has passed KYC. */
    AccountResponse createAccount(AccountRequest request);

    /** Bounded listing. An unbounded one would pull every account in the bank into memory. */
    List<AccountResponse> getAllAccounts(int page, int size);

    AccountResponse getAccountById(Long id);

    AccountResponse getAccountByNumber(String accountNumber);

    List<AccountResponse> getAccountsByCustomer(Long customerId);

    /** Changes the risk limits. Every altered field is written to the change history. */
    AccountResponse updateLimits(Long id, AccountLimitsRequest request);

    /** Freezes, reactivates or closes an account. */
    AccountResponse updateStatus(Long id, AccountStatusRequest request);

    /** Closes an account. Refused while it still holds a balance. */
    void closeAccount(Long id, String reason);

    List<AccountChangeHistoryResponse> getChangeHistory(Long id);

    /**
     * Ownership, currency and status of an account, for internal callers.
     *
     * <p>Carries no balance and no personal data, so an orchestrating service can cache the owner
     * for row-level authorization without ever holding customer information.</p>
     */
    Map<String, Object> getAccountSummary(Long id);
}
