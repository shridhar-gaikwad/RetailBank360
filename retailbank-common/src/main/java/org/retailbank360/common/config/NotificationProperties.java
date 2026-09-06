package org.retailbank360.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Binds {@code retailbank.notification.*} - where customer notifications are sent. */
@Getter
@Setter
@ConfigurationProperties(prefix = "retailbank.notification")
public class NotificationProperties {

    /** When false, events are only written to the local application log. */
    private boolean enabled = true;

    private String url = "http://localhost:8086";

    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration readTimeout = Duration.ofSeconds(5);

    /** Dispatch is asynchronous: a notification outage must never fail a banking transaction. */
    private int workerThreads = 2;

    private int queueCapacity = 500;
}
