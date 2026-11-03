package org.ipro.design.generation;

import java.util.*;
import org.ipro.design.CheckedStructure;
import org.ipro.design.DraftProblem;
import org.ipro.design.FieldDraft;
import org.ipro.design.RecipeCatalog.FieldType;

/** One standard-profile representability gate for early readiness and final generation. */
final class StandardRepresentability {
    private final boolean references;
    private final String fingerprintProfile;
    StandardRepresentability(boolean references, String fingerprintProfile) {
        this.references = references;
        this.fingerprintProfile = fingerprintProfile;
    }
    List<GenerationDiagnostic> check(CheckedStructure checked) {
        var source = checked.source();
        var diagnostics = new ArrayList<GenerationDiagnostic>();
        if (!references && source.schemaVersion() != 1) diagnostics.add(new GenerationDiagnostic(
                GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT, target(source.draftId(), null, null, "schemaVersion"),
                messageKey(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT), Map.of("construct", "ENVELOPE")));
        if (!references && checked.owner() != null) diagnostics.add(new GenerationDiagnostic(
                GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT, target(source.draftId(), null, null, "owner"),
                messageKey(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT), Map.of("construct", "OWNER")));
        if (checked.root().subsystemId() != null) diagnostics.add(new GenerationDiagnostic(
                GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT,
                target(source.draftId(), null, null, "subsystemId"),
                messageKey(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT),
                Map.of("subsystemId", checked.root().subsystemId())));
        if (!references) for (var section : checked.sections()) diagnostics.add(new GenerationDiagnostic(
                GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT,
                target(source.draftId(), section.id(), null, "sections"),
                messageKey(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT),
                unsupportedParameters("SECTION", Map.of("sectionLabel", section.label()))));
        for (var field : checked.fields()) {
            if (field.type() == FieldType.REFERENCE && !references) {
                diagnostics.add(new GenerationDiagnostic(
                        GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT,
                        target(source.draftId(), null, field.id(), "type"),
                        messageKey(GenerationDiagnostic.Code.UNSUPPORTED_CONSTRUCT),
                        unsupportedParameters("REFERENCE", Map.of("field", field.name(), "type", field.type().name()))));
            } else if (field.serverRequired() && field.uiRequired() == FieldDraft.UiRequired.OPTIONAL) {
                diagnostics.add(new GenerationDiagnostic(
                        GenerationDiagnostic.Code.UNREPRESENTABLE_REQUIRED_MODE,
                        target(source.draftId(), null, field.id(), "uiRequired"),
                        messageKey(GenerationDiagnostic.Code.UNREPRESENTABLE_REQUIRED_MODE),
                        Map.of("runtimeCode", "UI_OPTIONAL_SERVER_REQUIRED",
                                "serverRequired", "true", "uiRequired", "OPTIONAL")));
            }
        }
        if (references) for (var section : checked.sections()) for (var field : section.fields()) {
            if (field.serverRequired() && field.uiRequired() == FieldDraft.UiRequired.OPTIONAL)
                diagnostics.add(new GenerationDiagnostic(GenerationDiagnostic.Code.UNREPRESENTABLE_REQUIRED_MODE,
                    target(source.draftId(), section.id(), field.id(), "uiRequired"),
                    messageKey(GenerationDiagnostic.Code.UNREPRESENTABLE_REQUIRED_MODE),
                    Map.of("runtimeCode", "UI_OPTIONAL_SERVER_REQUIRED", "serverRequired", "true", "uiRequired", "OPTIONAL")));
        }
        if (diagnostics.isEmpty()) try {
            checked.applicationProjection();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            diagnostics.add(new GenerationDiagnostic(GenerationDiagnostic.Code.PROJECTION_UNREPRESENTABLE,
                target(source.draftId(), null, null, "applications"),
                messageKey(GenerationDiagnostic.Code.PROJECTION_UNREPRESENTABLE), Map.of()));
        }
        return List.copyOf(diagnostics);
    }

    private Map<String, String> unsupportedParameters(String construct, Map<String, String> details) {
        // The captured catalog profile retains its exact diagnostics contract.
        if (GenerationManifest.LEGACY_FINGERPRINT.equals(fingerprintProfile)) return details;
        var parameters = new java.util.HashMap<>(details);
        parameters.put("construct", construct);
        return parameters;
    }

    private static String messageKey(GenerationDiagnostic.Code code) {
        return "entityGeneration." + code.name();
    }

    private static DraftProblem.Target target(UUID draftId, UUID sectionId, UUID fieldId, String propertyKey) {
        return new DraftProblem.Target(draftId, sectionId, fieldId, propertyKey);
    }
}
