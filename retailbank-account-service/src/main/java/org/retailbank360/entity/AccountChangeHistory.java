package org.retailbank360.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Change history for the non-monetary but still critical attributes of an account: status, overdraft
 * permission, minimum balance and daily transfer limit.
 *
 * <p>Balance movements are already fully described by the ledger. This table covers the other half
 * of "change history for critical operations": who raised a customer's transfer ceiling, who froze
 * an account, and when.</p>
 */
@Entity
@Table(name = "account_change_history",
        indexes = {
                @Index(name = "ix_acct_history_account", columnList = "account_id"),
                @Index(name = "ix_acct_history_at", columnList = "changed_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class AccountChangeHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "field_name", nullable = false, length = 60)
    private String fieldName;

    @Column(name = "old_value", length = 255)
    private String oldValue;

    @Column(name = "new_value", length = 255)
    private String newValue;

    @Column(name = "changed_by", nullable = false, length = 120)
    private String changedBy;

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    @PreUpdate
    @PreRemove
    private void rejectMutation() {
        throw new UnsupportedOperationException("account_change_history is append-only");
    }

    public static AccountChangeHistory of(Long accountId, String field, Object oldValue, Object newValue,
                                          String changedBy, String reason) {
        AccountChangeHistory history = new AccountChangeHistory();
        history.setAccountId(accountId);
        history.setFieldName(field);
        history.setOldValue(oldValue == null ? null : String.valueOf(oldValue));
        history.setNewValue(newValue == null ? null : String.valueOf(newValue));
        history.setChangedBy(changedBy);
        history.setReason(reason);
        history.setChangedAt(LocalDateTime.now());
        return history;
    }
}
