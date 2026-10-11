package org.ipro.navigation;

/**
 * Application declaration of stable identities and initial visibility for existing metadata.
 * A declaration cannot create a subsystem, an entity, membership or a form.
 */
@FunctionalInterface
public interface NavigationDefaultsContributor {
    void contribute(NavigationDefaults defaults);
}
