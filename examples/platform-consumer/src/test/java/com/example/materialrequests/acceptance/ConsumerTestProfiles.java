package com.example.materialrequests.acceptance;

import org.springframework.test.context.ActiveProfilesResolver;

/** The PostgreSQL gate must never silently fall back to the embedded smoke database. */
public class ConsumerTestProfiles implements ActiveProfilesResolver {
    @Override public String[] resolve(Class<?> testClass) {
        return new String[]{switch (System.getProperty("consumer.test.database", "h2")) {
            case "h2" -> "bootstrap-smoke";
            case "postgres" -> "postgres-acceptance";
            default -> throw new IllegalArgumentException("Unknown consumer.test.database");
        }};
    }
}
