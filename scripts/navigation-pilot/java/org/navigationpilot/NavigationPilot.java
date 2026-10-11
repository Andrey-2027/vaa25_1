package org.navigationpilot;

import org.ip.Application;
import org.springframework.boot.SpringApplication;

/** Separate launcher: neither this class nor the fixture is shipped in the application jar. */
public final class NavigationPilot {
    public static void main(String[] args) {
        SpringApplication.run(new Class<?>[] {Application.class, PilotConfiguration.class}, args);
    }
}
