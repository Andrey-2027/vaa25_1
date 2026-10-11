package org.ipro.register;

import org.springframework.beans.factory.SmartInitializingSingleton;
import java.util.Objects;

/** Validates all managed registers before refresh completes, even without a reader. */
public final class RegisterStartupValidator implements SmartInitializingSingleton {
    private final RegisterRegistry registry;
    private final RecorderOrderClockBootstrap recorderOrderClockBootstrap;

    public RegisterStartupValidator(RegisterRegistry registry) {
        this(registry, null);
    }

    public RegisterStartupValidator(RegisterRegistry registry,
                                    RecorderOrderClockBootstrap recorderOrderClockBootstrap) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.recorderOrderClockBootstrap = recorderOrderClockBootstrap;
    }

    @Override
    public void afterSingletonsInstantiated() {
        registry.initialize();
        if (recorderOrderClockBootstrap != null) {
            recorderOrderClockBootstrap.initialize(registry.descriptors());
        }
    }
}
