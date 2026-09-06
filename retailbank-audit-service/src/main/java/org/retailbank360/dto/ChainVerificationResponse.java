package org.retailbank360.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * Result of walking the audit hash chains.
 *
 * <p>The trail is sharded by source service, so verification walks one chain per service and reports
 * each separately. {@link #intact} is true only when every shard is intact.</p>
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChainVerificationResponse {

    private boolean intact;

    private long eventsChecked;

    private int shardsChecked;

    /** Per-service results; a broken shard names the first event that does not verify. */
    private List<ShardVerification> shards;

    private Instant verifiedAt;

    private String verifiedBy;

    /** One service's chain. */
    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ShardVerification {

        private String sourceService;

        private boolean intact;

        private long eventsChecked;

        /** Id of the first row whose hash does not match, when this shard is broken. */
        private Long firstBrokenEventId;

        private String problem;
    }
}
