package org.ipro.design.runtime;

import org.ipro.design.RuntimeDesignSnapshot.RegisterFacts;
import org.ipro.register.RegisterDescriptor;
import org.ipro.register.RegisterRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Bridge from the already validated {@link RegisterRegistry} to portable facts. It never scans the
 * classpath and never re-describes a type: the registry is the single startup owner that published
 * this declaration snapshot, and an invalid declaration fails here instead of becoming empty facts.
 */
public final class RegisterRegistryFactsSource implements RegisterFactsSource {
    private final RegisterRegistry registers;
    private final java.util.function.BooleanSupplier engineAvailable;

    public RegisterRegistryFactsSource(RegisterRegistry registers) {
        this(registers, () -> false);
    }
    RegisterRegistryFactsSource(RegisterRegistry registers, java.util.function.BooleanSupplier engineAvailable) {
        this.registers = Objects.requireNonNull(registers, "registers");
        this.engineAvailable = Objects.requireNonNull(engineAvailable);
    }
    @Override public org.ipro.design.RuntimeDesignSnapshot.Prerequisite operations() {
        registers.descriptors(); // the registry remains the declaration/startup owner
        return engineAvailable.getAsBoolean()
            ? new org.ipro.design.RuntimeDesignSnapshot.Prerequisite(org.ipro.design.RuntimeDesignSnapshot.Status.AVAILABLE,
                "Installed readers, key writes, named operations, key guard and recorder clock")
            : RegisterFactsSource.super.operations();
    }

    @Override
    public Map<String, RegisterFacts> facts() {
        Map<String, RegisterFacts> facts = new TreeMap<>();
        for (RegisterDescriptor descriptor : registers.descriptors()) {
            RegisterFacts portable = EntityDesignRuntimeProjection.registerFacts(descriptor);
            if (facts.put(portable.typeId(), portable) != null)
                throw new IllegalStateException("Duplicate register facts: " + portable.typeId());
        }
        return Map.copyOf(facts);
    }
}
