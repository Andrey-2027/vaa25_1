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

    public RegisterRegistryFactsSource(RegisterRegistry registers) {
        this.registers = Objects.requireNonNull(registers, "registers");
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
