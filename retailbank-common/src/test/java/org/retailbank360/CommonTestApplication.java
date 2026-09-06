package org.retailbank360;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal host application for the shared-module integration tests.
 *
 * <p>Lives in {@code org.retailbank360} so entity scanning and repository scanning pick up
 * {@code org.retailbank360.common.**} exactly as they do in a real service.</p>
 */
@SpringBootApplication
public class CommonTestApplication {
}
