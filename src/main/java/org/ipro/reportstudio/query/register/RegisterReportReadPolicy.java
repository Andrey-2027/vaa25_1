package org.ipro.reportstudio.query.register;

import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.SingularAttribute;
import org.ipro.register.RegisterDescriptor;
import org.ipro.register.RegisterRegistry;
import org.ipro.reportstudio.query.ReportReadPolicy;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;

import java.util.*;

/** Только политики с постоянной видимостью всех версий полного набора измерений. */
public final class RegisterReportReadPolicy implements ReportReadPolicy {
    private final EntityManagerFactory emf;
    private final RlsDimensionRegistry registry;
    private final RegisterRegistry registers;

    public RegisterReportReadPolicy(EntityManagerFactory emf, RlsDimensionRegistry registry, RegisterRegistry registers) {
        this.emf = Objects.requireNonNull(emf);
        this.registry = Objects.requireNonNull(registry);
        this.registers = Objects.requireNonNull(registers);
    }

    @Override
    public List<String> violations(Set<Class<?>> entityTypes) {
        List<String> errors = new ArrayList<>();
        for (Class<?> type : entityTypes) {
            var descriptor = registers.find(type);
            if (descriptor.isEmpty()) continue;
            var metadata = descriptor.orElseThrow();
            var policy = registry.policyOf(type);
            for (var entry : policy.dimensions().entrySet()) {
                if (entry.getValue() == RlsDimensionKind.CHECK_ONLY) continue;
                var rule = policy.valueRules().get(entry.getKey());
                boolean invariant = !rule.custom() && rule.paths().stream()
                    .allMatch(path -> immutableDimensionPath(type, metadata, path));
                if (!invariant) {
                    errors.add("REGISTER_SLICE_VISIBILITY: " + type.getName() + " / " + entry.getKey()
                        + " — RLS должен зависеть только от неизменяемых измерений; paths=" + rule.paths());
                }
            }
        }
        return List.copyOf(errors);
    }

    private boolean immutableDimensionPath(Class<?> type, RegisterDescriptor metadata, String path) {
        // Вложенные mutable-свойства ссылки не принимаются по совпадению строкового префикса.
        var entity = emf.getMetamodel().entity(type);
        for (String dimension : metadata.dimensionNames()) {
            Attribute<?, ?> attribute = entity.getAttribute(dimension);
            if (attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.BASIC) {
                if (path.equals(dimension) && Number.class.isAssignableFrom(attribute.getJavaType())) return true;
            } else if (attribute instanceof SingularAttribute<?, ?> singular && singular.isAssociation()
                && (attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.MANY_TO_ONE
                    || attribute.getPersistentAttributeType() == Attribute.PersistentAttributeType.ONE_TO_ONE)) {
                var target = emf.getMetamodel().entity(attribute.getJavaType());
                if (target.hasSingleIdAttribute()) {
                    var id = target.getId(target.getIdType().getJavaType());
                    if (path.equals(dimension + "." + id.getName())) return true;
                }
            }
        }
        return false;
    }
}
