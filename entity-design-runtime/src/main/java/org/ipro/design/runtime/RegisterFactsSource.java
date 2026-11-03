package org.ipro.design.runtime;

import org.ipro.design.RuntimeDesignSnapshot.RegisterFacts;

import java.util.Map;

/**
 * Optional source of validated register facts. Absence of this source is a fact gap, not an empty
 * registry: {@code null} from the provider and an empty map must stay distinguishable.
 */
interface RegisterFactsSource {
    /** Register facts keyed by Java type name; empty means the owner checked and found none. */
    Map<String, RegisterFacts> facts();
    default org.ipro.design.RuntimeDesignSnapshot.Prerequisite operations() {
        return new org.ipro.design.RuntimeDesignSnapshot.Prerequisite(
            org.ipro.design.RuntimeDesignSnapshot.Status.UNAVAILABLE, "Register engine beans were not confirmed");
    }
}
