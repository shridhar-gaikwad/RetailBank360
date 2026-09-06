package org.retailbank360.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Binds {@code retailbank.audit.*} - where business audit events are shipped. */
@Getter
@Setter
@ConfigurationProperties(prefix = "retailbank.audit")
public class AuditProperties {

    /** When false, events are only written to the local application log. */
    private boolean enabled = true;

    /** Base URL of the audit-service. */
    private String url = "http://localhost:8087";

    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration readTimeout = Duration.ofSeconds(5);

    /** Publishing is asynchronous so an audit outage can never fail a banking transaction. */
    private int workerThreads = 2;

    private int queueCapacity = 500;
}
