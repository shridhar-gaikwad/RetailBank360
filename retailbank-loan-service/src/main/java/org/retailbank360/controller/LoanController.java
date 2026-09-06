package org.retailbank360.controller;

import jakarta.validation.Valid;
import org.retailbank360.common.constants.Roles;
import org.retailbank360.dto.EligibilityResponse;
import org.retailbank360.dto.LoanApplicationRequest;
import org.retailbank360.dto.LoanDecisionRequest;
import org.retailbank360.dto.LoanResponse;
import org.retailbank360.dto.RepaymentRequest;
import org.retailbank360.dto.RepaymentScheduleResponse;
import org.retailbank360.service.LoanService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Loan origination and servicing endpoints.
 *
 * <p>Approval and disbursement are loan-officer work; application, repayment and read access belong
 * to the customer who owns the loan, enforced per row inside the service.</p>
 */
@RestController
@RequestMapping("/api/v1/loans")
public class LoanController {

    private final LoanService loanService;

    public LoanController(LoanService loanService) {
        this.loanService = loanService;
    }

    /** Scores an application without submitting it. */
    @PostMapping("/eligibility")
    public ResponseEntity<EligibilityResponse> checkEligibility(
            @Valid @RequestBody LoanApplicationRequest request) {
        return ResponseEntity.ok(loanService.checkEligibility(request));
    }

    /** Submits an application. An ineligible application is recorded as REJECTED with its reasons. */
    @PostMapping
    public ResponseEntity<LoanResponse> apply(@Valid @RequestBody LoanApplicationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(loanService.apply(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<LoanResponse> getLoan(@PathVariable Long id) {
        return ResponseEntity.ok(loanService.getLoan(id));
    }

    @GetMapping("/ref/{loanRef}")
    public ResponseEntity<LoanResponse> getLoanByRef(@PathVariable String loanRef) {
        return ResponseEntity.ok(loanService.getLoanByRef(loanRef));
    }

    @GetMapping("/all")
    @PreAuthorize(Roles.HAS_STAFF)
    public ResponseEntity<List<LoanResponse>> getAllLoans(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(loanService.getAllLoans(page, size));
    }

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<LoanResponse>> getLoansByCustomer(@PathVariable Long customerId) {
        return ResponseEntity.ok(loanService.getLoansByCustomer(customerId));
    }

    @GetMapping("/{id}/schedule")
    public ResponseEntity<List<RepaymentScheduleResponse>> getSchedule(@PathVariable Long id) {
        return ResponseEntity.ok(loanService.getSchedule(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(Roles.HAS_LOAN_OFFICER_OR_ADMIN)
    public ResponseEntity<LoanResponse> approve(@PathVariable Long id,
                                                @Valid @RequestBody LoanDecisionRequest request) {
        return ResponseEntity.ok(loanService.approve(id, request));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(Roles.HAS_LOAN_OFFICER_OR_ADMIN)
    public ResponseEntity<LoanResponse> reject(@PathVariable Long id,
                                               @Valid @RequestBody LoanDecisionRequest request) {
        return ResponseEntity.ok(loanService.reject(id, request));
    }

    /**
     * Funds an approved loan.
     *
     * <p>Holds a lock on both the loan and the destination account for the duration, so a second
     * officer attempting the same disbursement gets 423 rather than a double credit.</p>
     */
    @PostMapping("/{id}/disburse")
    @PreAuthorize(Roles.HAS_LOAN_OFFICER_OR_ADMIN)
    public ResponseEntity<LoanResponse> disburse(@PathVariable Long id) {
        return ResponseEntity.ok(loanService.disburse(id));
    }

    /** Collects a repayment and applies it to the oldest outstanding instalments. */
    @PostMapping("/{id}/repay")
    public ResponseEntity<LoanResponse> repay(
            @PathVariable Long id,
            @Valid @RequestBody RepaymentRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        if ((request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank())
                && idempotencyKey != null && !idempotencyKey.isBlank()) {
            request.setIdempotencyKey(idempotencyKey);
        }
        return ResponseEntity.ok(loanService.repay(id, request));
    }

    /** Forces an immediate recovery sweep over loans stuck mid-disbursement. */
    @PostMapping("/recover-disbursements")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<Map<String, Object>> recoverDisbursements() {
        return ResponseEntity.ok(Map.of("settled", loanService.recoverStuckDisbursements()));
    }

    @PostMapping("/mark-overdue")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<Map<String, Object>> markOverdue() {
        return ResponseEntity.ok(Map.of("marked", loanService.markOverdueInstallments()));
    }

    /** Forces an immediate EMI collection sweep instead of waiting for the nightly one. */
    @PostMapping("/collect-due")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<Map<String, Object>> collectDue() {
        return ResponseEntity.ok(Map.of("collected", loanService.collectDueInstallments()));
    }

    @PostMapping("/mark-defaulted")
    @PreAuthorize(Roles.HAS_ADMIN)
    public ResponseEntity<Map<String, Object>> markDefaulted() {
        return ResponseEntity.ok(Map.of("defaulted", loanService.markDefaultedLoans()));
    }
}
