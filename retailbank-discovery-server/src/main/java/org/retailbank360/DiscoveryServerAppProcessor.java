package org.retailbank360;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * Service registry.
 *
 * <p>Lets the services find each other by name instead of by hard-coded URL, and gives Feign a set of
 * live instances to balance across rather than a single address. That second part matters here: the
 * distributed lock exists precisely so several instances of one service can run at once, and
 * discovery is what makes scaling to those instances practical.</p>
 *
 * <p>Optional by design. Every service defaults to {@code eureka.client.enabled=false} and its
 * configured URLs, so the POC still runs with nothing but the services themselves. Start this and use
 * the {@code discovery} profile to switch the platform over.</p>
 *
 * <p>Dashboard: {@code http://localhost:8761}</p>
 */
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServerAppProcessor {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServerAppProcessor.class, args);
    }
}
