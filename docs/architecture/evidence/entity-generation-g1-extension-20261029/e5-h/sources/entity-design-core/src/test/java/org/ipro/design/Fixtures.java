package org.ipro.design;

import java.io.IOException;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

final class Fixtures {
    static final ObjectMapper JSON = new ObjectMapper(); // Test adapter only; production codec is P8.4.
    static EntityDraft draft(String name) {
        try (var stream = Fixtures.class.getResourceAsStream("/org/ipro/design/fixtures/" + name + ".json")) {
            return JSON.readValue(Objects.requireNonNull(stream), EntityDraft.class);
        } catch (IOException ex) { throw new AssertionError(ex); }
    }
    static ProjectDesignContext context() {
        var targets = new HashMap<String, ReferenceTargetDescriptor>();
        for (String name : List.of("UnitOfMeasurement", "Nomenclature")) {
            String fqn = "org.ip.model." + name;
            targets.put(fqn, new ReferenceTargetDescriptor(fqn, name, "STANDARD_ROOT", "java.lang.Long",
                    ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Eligibility.SUPPORTED, ""));
        }
        return new ProjectDesignContext("test-project", "v1", "org.ip.model", names(targets.keySet()), names(Set.of("nomenclature")),
                names(Set.of("org.ip.subsystem.Subsystems$Directories")), names(Set.of("FILE")), targets, true);
    }
    static ProjectDesignContext.Names names(Set<String> values) { return ProjectDesignContext.Names.known(values); }
    static EntityDraft valid() { return draft("catalog-two-sections"); }
    static EntityDraft field(EntityDraft draft, FieldDraft field) { return draft.withFields(List.of(field)); }
    static FieldDraft field(FieldDraft old, String name, String type, ConstraintDraft constraints, ReferenceDraft reference,
                            DraftText column, boolean required, FieldDraft.UiRequired ui, FieldApplications apps) {
        return new FieldDraft(old.id(), name, old.label(), type, column, required, ui, constraints, reference, apps);
    }
}
