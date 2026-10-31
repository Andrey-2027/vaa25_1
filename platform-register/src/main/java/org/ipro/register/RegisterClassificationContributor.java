package org.ipro.register;

import org.ipro.data.EntityClassificationContributor;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.annotation.EntityKind;
import java.util.Objects;
import java.util.Optional;

/** Uses the validated PU registry, never a marker alone. No dependency on the final catalog. */
public final class RegisterClassificationContributor implements EntityClassificationContributor {
    private final RegisterRegistry registry;

    public RegisterClassificationContributor(RegisterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
        registry.initialize();
    }

    @Override
    public Optional<Contribution> contribute(Class<?> type) {
        return registry.find(type).map(descriptor -> new Contribution(EntityKind.INFORMATION_REGISTER,
            FactOrigin.DERIVED, descriptor.entityType().getName(), descriptor.recorded()));
    }
}
