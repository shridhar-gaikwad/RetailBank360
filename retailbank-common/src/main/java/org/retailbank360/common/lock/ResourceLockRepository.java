package org.retailbank360.common.lock;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for the distributed lock registry.
 *
 * <p>Every state change is expressed as a single conditional {@code UPDATE}. That is what makes the
 * lock correct across processes and nodes: the database applies the row update atomically, so
 * exactly one of N competing instances can observe {@code rowsAffected == 1}. No read-then-write
 * sequence, and therefore no window in which two callers both believe they won.</p>
 */
@Repository
public interface ResourceLockRepository extends JpaRepository<ResourceLock, Long> {

    Optional<ResourceLock> findByLockKey(String lockKey);

    boolean existsByLockKey(String lockKey);

    /**
     * Compare-and-set acquisition. Succeeds when the lock is free <em>or</em> when the previous
     * holder let its TTL lapse, which is the immediate orphan-recovery path.
     *
     * @return 1 when this caller took the lock, 0 when somebody else holds it
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ResourceLock l
               set l.ownerToken   = :ownerToken,
                   l.ownerNode    = :ownerNode,
                   l.ownerUser    = :ownerUser,
                   l.operationId  = :operationId,
                   l.acquiredAt   = :now,
                   l.expiresAt    = :expiresAt,
                   l.releasedAt   = null,
                   l.fencingToken = l.fencingToken + 1,
                   l.acquireCount = l.acquireCount + 1
             where l.lockKey = :lockKey
               and (l.ownerToken is null or l.expiresAt is null or l.expiresAt <= :now)
            """)
    int acquireIfFree(@Param("lockKey") String lockKey,
                      @Param("ownerToken") String ownerToken,
                      @Param("ownerNode") String ownerNode,
                      @Param("ownerUser") String ownerUser,
                      @Param("operationId") String operationId,
                      @Param("now") Instant now,
                      @Param("expiresAt") Instant expiresAt);

    /** Releases the lock only if this caller still owns it. 0 means the lock had already been lost. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ResourceLock l
               set l.ownerToken  = null,
                   l.expiresAt   = null,
                   l.releasedAt  = :now
             where l.lockKey = :lockKey
               and l.ownerToken = :ownerToken
            """)
    int releaseIfOwner(@Param("lockKey") String lockKey,
                       @Param("ownerToken") String ownerToken,
                       @Param("now") Instant now);

    /** Extends the TTL of a lock this caller still owns, for long running batch work. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update ResourceLock l
               set l.expiresAt = :expiresAt
             where l.lockKey = :lockKey
               and l.ownerToken = :ownerToken
               and l.expiresAt > :now
            """)
    int renewIfOwner(@Param("lockKey") String lockKey,
                     @Param("ownerToken") String ownerToken,
                     @Param("now") Instant now,
                     @Param("expiresAt") Instant expiresAt);

    /** Locks whose owner is gone and whose TTL lapsed before the cutoff, i.e. crash leftovers. */
    @Query("""
            select l from ResourceLock l
             where l.ownerToken is not null
               and (l.expiresAt is null or l.expiresAt <= :cutoff)
            """)
    List<ResourceLock> findOrphaned(@Param("cutoff") Instant cutoff);

    /** Everything currently held, newest first. Backs the administrative lock view. */
    @Query("""
            select l from ResourceLock l
             where l.ownerToken is not null
             order by l.acquiredAt desc
            """)
    List<ResourceLock> findHeld();

    @Query("""
            select l from ResourceLock l
             where l.ownerToken is not null
               and l.resourceType = :resourceType
             order by l.acquiredAt desc
            """)
    List<ResourceLock> findHeldByType(@Param("resourceType") String resourceType);
}
