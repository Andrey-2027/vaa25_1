package org.ipro.rest.web.config;

/**
 * Marker bean produced by {@link RestWebAutoConfiguration} after the enablement checks pass.
 *
 * <p>Its presence is the observable fact that the web namespace is consistently enabled: owner
 * context tests assert it directly, so "module active" is defined by a bean rather than by a
 * missing failure. Later packages extend this guard instead of adding parallel flags (read
 * gateway in 4.2, identity/security collaborators in 4.3).</p>
 */
final class RestWebStartupCheck {
}
