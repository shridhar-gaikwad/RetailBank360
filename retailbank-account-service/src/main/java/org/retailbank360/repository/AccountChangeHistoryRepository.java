package org.retailbank360.repository;

import org.retailbank360.entity.AccountChangeHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Read access to the append-only account change history. */
@Repository
public interface AccountChangeHistoryRepository extends JpaRepository<AccountChangeHistory, Long> {

    List<AccountChangeHistory> findByAccountIdOrderByChangedAtDesc(Long accountId);
}
