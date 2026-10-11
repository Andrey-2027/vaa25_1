package org.ipro.rest.web.config;

import org.ipro.rest.web.rls.RestAwareRlsReadableIdsResolver;
import org.ipro.rest.web.rls.RestReadableIdsCache;
import org.ipro.rls.RlsReadableIdsCache;
import org.ipro.rls.config.RlsAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Registers the request-aware {@code RlsReadableIdsResolver} before {@link RlsAutoConfiguration}
 * (F-REST-READ-4 §4.2), so the delegating default resolver backs off and exactly one independent
 * source exists.
 *
 * <p>The split from {@link RestWebAutoConfiguration} is deliberate: that configuration is ordered
 * after the server-side read pipeline, whose configuration chain already orders
 * {@code RlsAutoConfiguration} before the REST configs. A single class carrying both
 * {@code @AutoConfigureAfter(read)} and {@code @AutoConfigureBefore(rls)} would close an
 * AutoConfigure cycle; this tiny configuration carries only the ordering the seam needs.</p>
 */
@AutoConfiguration
@AutoConfigureBefore(RlsAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "platform.rest.web.enabled", havingValue = "true")
public class RestRlsResolverAutoConfiguration {

    /**
     * The bean is deliberately not conditional on a missing resolver: a second independent
     * resolver is a configuration error that stops startup at the activator, not a silent
     * replacement of one source by another. The session cache is only ever dereferenced on the
     * UI path through its provider.
     */
    @Bean
    RestAwareRlsReadableIdsResolver restAwareRlsReadableIdsResolver(
            ObjectProvider<RestReadableIdsCache> requestCacheProvider,
            ObjectProvider<RlsReadableIdsCache> sessionCacheProvider) {
        return new RestAwareRlsReadableIdsResolver(requestCacheProvider, sessionCacheProvider);
    }
}
