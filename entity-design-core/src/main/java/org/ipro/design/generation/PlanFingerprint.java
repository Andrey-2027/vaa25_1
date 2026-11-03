package org.ipro.design.generation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ipro.design.DraftText;
import org.ipro.design.EntityDraft;
import org.ipro.design.EntityProperties;
import org.ipro.design.FieldDraft;
import org.ipro.design.ProjectDesignContext;
import org.ipro.design.RecipeRef;
import org.ipro.design.ReferenceTargetDescriptor;
import org.ipro.design.RuntimeDesignSnapshot;
import org.ipro.design.SectionDraft;
import org.ipro.design.CheckedStructure;
import org.ipro.design.FieldApplicationMapping;

/**
 * Canonical, locale-independent fingerprint of a plan. Lists keep their order, maps/sets are
 * sorted, strings are length-prefixed and null is distinct from an empty value.
 */
final class PlanFingerprint {
    private static final String FORMAT = "plan-fingerprint-v1";

    private PlanFingerprint() { }

    static String planId(RecipeRef recipe, String generatorVersion, EntityDraft source,
                         ProjectDesignContext context, List<GenerationFile> files) {
        return planId(FORMAT, recipe, generatorVersion, source, context, files);
    }

    static String planId(String profile, RecipeRef recipe, String generatorVersion, EntityDraft source,
                         ProjectDesignContext context, List<GenerationFile> files) {
        return planId(profile, recipe, generatorVersion, source, context, files, null, null);
    }

    static String planId(String profile, RecipeRef recipe, String generatorVersion, EntityDraft source,
                         ProjectDesignContext context, List<GenerationFile> files,
                         CheckedStructure.Owner owner, FieldApplicationMapping.OwnerBinding binding) {
        if (!FORMAT.equals(profile) && !GenerationManifest.EXTENSION_FINGERPRINT.equals(profile)
                && !GenerationManifest.REGISTER_FINGERPRINT.equals(profile))
            throw new IllegalArgumentException("Unsupported fingerprint profile");
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) {
            text(out, profile);
            recipe(out, recipe);
            text(out, generatorVersion);
            draft(out, source);
            context(out, context);
            if (GenerationManifest.EXTENSION_FINGERPRINT.equals(profile)) {
                var keys = sorted(context.referenceTargets());
                out.writeInt(keys.size());
                for (var key : keys) {
                    text(out, key);
                    text(out, context.referenceTargets().get(key).sourceTypeName());
                }
                var rawOwner = source.owner();
                out.writeBoolean(rawOwner != null);
                if (rawOwner != null) {
                    text(out, rawOwner.targetTypeId()); text(out, rawOwner.fieldName());
                    draftText(out, rawOwner.columnName()); text(out, rawOwner.label());
                }
                out.writeBoolean(owner != null);
                if (owner != null) {
                    text(out, owner.targetTypeId()); text(out, owner.fieldName()); text(out, owner.columnName());
                    text(out, owner.label()); out.writeInt(owner.order());
                    out.writeBoolean(owner.applications().form()); out.writeBoolean(owner.applications().list());
                    out.writeBoolean(owner.applications().selection()); out.writeBoolean(owner.applications().table());
                }
                out.writeBoolean(binding != null);
                if (binding != null) {
                    text(out, binding.property()); out.writeInt(binding.order());
                    out.writeBoolean(binding.emitFieldMetadata()); out.writeBoolean(binding.hidden());
                    out.writeBoolean(binding.gridVisible()); text(out, binding.requiredMode()); out.writeBoolean(binding.serverRequired());
                }
                var typeKeys = sorted(context.runtimeSnapshot().types());
                out.writeInt(typeKeys.size());
                for (var key : typeKeys) {
                    text(out, key);
                    var facts = context.runtimeSnapshot().types().get(key).owner();
                    out.writeBoolean(facts != null);
                    if (facts != null) { text(out, facts.property()); text(out, facts.targetTypeId()); }
                }
            } else if (GenerationManifest.REGISTER_FINGERPRINT.equals(profile)) {
                var keys = sorted(context.referenceTargets());
                out.writeInt(keys.size());
                for (var key : keys) {
                    text(out, key);
                    text(out, context.referenceTargets().get(key).sourceTypeName());
                }
                registerDraft(out, source);
                referencedRegisterFacts(out, source, context);
            }
            out.writeInt(files.size());
            for (var file : files) {
                text(out, file.operation().name());
                text(out, file.relativePath());
                text(out, file.sha256());
                if (GenerationManifest.EXTENSION_FINGERPRINT.equals(profile)
                        || GenerationManifest.REGISTER_FINGERPRINT.equals(profile))
                    out.writeLong(file.content().getBytes(StandardCharsets.UTF_8).length);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return "g1-" + GenerationHash.sha256(buffer.toByteArray());
    }

    private static void draft(DataOutputStream out, EntityDraft draft) throws IOException {
        out.writeInt(draft.schemaVersion());
        recipe(out, draft.recipe());
        id(out, draft.draftId());
        entity(out, draft.entity());
        out.writeInt(draft.fields().size());
        for (var field : draft.fields()) field(out, field);
        out.writeInt(draft.sections().size());
        for (var section : draft.sections()) section(out, section);
    }

    private static void entity(DataOutputStream out, EntityProperties entity) throws IOException {
        text(out, entity.className());
        draftText(out, entity.packageName());
        text(out, entity.label());
        draftText(out, entity.titles().list());
        draftText(out, entity.titles().item());
        draftText(out, entity.titles().selection());
        draftText(out, entity.tableName());
        text(out, entity.subsystemId());
        text(out, entity.icon());
    }

    private static void field(DataOutputStream out, FieldDraft field) throws IOException {
        id(out, field.id());
        text(out, field.name());
        text(out, field.label());
        text(out, field.type());
        draftText(out, field.columnName());
        out.writeBoolean(field.serverRequired());
        text(out, field.uiRequired().name());
        text(out, field.constraints().length());
        text(out, field.constraints().precision());
        text(out, field.constraints().scale());
        if (field.reference() == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            text(out, field.reference().typeId());
        }
        var applications = field.applications();
        out.writeBoolean(applications.form());
        out.writeBoolean(applications.list());
        out.writeBoolean(applications.selection());
        out.writeBoolean(applications.table());
    }

    private static void section(DataOutputStream out, SectionDraft section) throws IOException {
        id(out, section.id());
        text(out, section.label());
        text(out, section.rowClassName());
        draftText(out, section.packageName());
        draftText(out, section.tableName());
        draftText(out, section.parentFieldName());
        draftText(out, section.parentColumnName());
        out.writeInt(section.fields().size());
        for (var field : section.fields()) field(out, field);
    }

    private static void context(DataOutputStream out, ProjectDesignContext context) throws IOException {
        text(out, context.projectId());
        text(out, context.version());
        text(out, context.defaultPackage());
        names(out, context.existingTypes());
        names(out, context.existingTables());
        names(out, context.subsystemIds());
        names(out, context.iconIds());
        var targetKeys = sorted(context.referenceTargets());
        out.writeInt(targetKeys.size());
        for (var key : targetKeys) {
            var target = context.referenceTargets().get(key);
            text(out, key);
            text(out, target.typeId());
            text(out, target.caption());
            text(out, target.exposure());
            text(out, target.identityType());
            text(out, target.representation().name());
            text(out, target.selection().name());
            text(out, target.eligibility().name());
            text(out, target.reason());
        }
        out.writeBoolean(context.referencesComplete());
        snapshot(out, context.runtimeSnapshot());
    }

    private static void names(DataOutputStream out, ProjectDesignContext.Names names) throws IOException {
        out.writeBoolean(names.complete());
        text(out, names.reason());
        stringsSorted(out, names.values());
    }

    private static void snapshot(DataOutputStream out, RuntimeDesignSnapshot snapshot) throws IOException {
        text(out, snapshot.snapshotId());
        out.writeBoolean(snapshot.complete());
        var typeKeys = sorted(snapshot.types());
        out.writeInt(typeKeys.size());
        for (var key : typeKeys) typeFacts(out, snapshot.types().get(key));
        var prerequisiteKeys = sorted(snapshot.prerequisites());
        out.writeInt(prerequisiteKeys.size());
        for (var key : prerequisiteKeys) {
            text(out, key);
            var prerequisite = snapshot.prerequisites().get(key);
            text(out, prerequisite.status().name());
            text(out, prerequisite.evidence());
        }
        out.writeInt(snapshot.diagnostics().size());
        for (var diagnostic : snapshot.diagnostics()) {
            text(out, diagnostic.severity());
            text(out, diagnostic.code());
            text(out, diagnostic.typeId());
            text(out, diagnostic.propertyId());
            text(out, diagnostic.source());
            text(out, diagnostic.message());
        }
    }

    private static void typeFacts(DataOutputStream out, RuntimeDesignSnapshot.TypeFacts type) throws IOException {
        text(out, type.typeId());
        text(out, type.kind());
        text(out, type.kindOrigin());
        text(out, type.kindSource());
        text(out, type.exposure());
        text(out, type.capabilitiesOrigin());
        text(out, type.capabilitiesSource());
        text(out, type.identityOrigin());
        text(out, type.identitySource());
        stringsSorted(out, type.reads());
        stringsSorted(out, type.writes());
        out.writeBoolean(type.interned());
        out.writeBoolean(type.recorderOwned());
        text(out, type.sectionOwnerTypeId());
        stringsOrdered(out, type.listColumns());
        stringsOrdered(out, type.selectColumns());
        out.writeInt(type.attributes().size());
        for (var attribute : type.attributes()) attribute(out, attribute);
        out.writeInt(type.sections().size());
        for (var section : type.sections()) ownedSection(out, section);
        out.writeInt(type.naturalKeys().size());
        for (var naturalKey : type.naturalKeys()) naturalKey(out, naturalKey);
        text(out, type.creationChannel());
        stringsOrdered(out, type.specialOperations());
    }

    private static void attribute(DataOutputStream out, RuntimeDesignSnapshot.Attribute attribute) throws IOException {
        text(out, attribute.propertyId());
        text(out, attribute.name());
        text(out, attribute.declaringTypeId());
        text(out, attribute.javaType());
        text(out, attribute.technicalPurpose());
        out.writeBoolean(attribute.required());
        text(out, attribute.requiredOrigin());
        out.writeBoolean(attribute.form());
        out.writeBoolean(attribute.list());
        out.writeBoolean(attribute.selection());
        out.writeBoolean(attribute.table());
        if (attribute.numbering() == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            numbering(out, attribute.numbering());
        }
    }

    private static void numbering(DataOutputStream out, RuntimeDesignSnapshot.Numbering numbering) throws IOException {
        text(out, numbering.role());
        stringsOrdered(out, numbering.scope());
        out.writeBoolean(numbering.allowManual());
        text(out, numbering.period());
        text(out, numbering.prefix());
        text(out, numbering.pattern());
        text(out, numbering.dateField());
    }

    private static void ownedSection(DataOutputStream out, RuntimeDesignSnapshot.OwnedSection section) throws IOException {
        text(out, section.sectionId());
        text(out, section.rowTypeId());
        text(out, section.parentProperty());
        text(out, section.persistence());
        text(out, section.rlsPolicy());
    }

    private static void naturalKey(DataOutputStream out, RuntimeDesignSnapshot.NaturalKey naturalKey) throws IOException {
        text(out, naturalKey.constraint());
        stringsOrdered(out, naturalKey.properties());
        stringsOrdered(out, naturalKey.nullProperties());
        text(out, naturalKey.normalization());
    }

    private static void registerDraft(DataOutputStream out, EntityDraft source) throws IOException {
        var register = source.register();
        out.writeBoolean(register != null);
        if (register == null) return;
        text(out, register.period());
        text(out, register.writeProfile());
        out.writeInt(register.dimensions().size());
        for (var id : register.dimensions()) id(out, id);
        out.writeInt(register.resources().size());
        for (var id : register.resources()) id(out, id);
    }

    private static void referencedRegisterFacts(DataOutputStream out, EntityDraft source,
                                                ProjectDesignContext context) throws IOException {
        var referenced = new java.util.TreeSet<String>();
        var register = source.register();
        if (register != null) for (var field : source.fields()) {
            if (register.dimensions().contains(field.id()) && field.reference() != null)
                referenced.add(field.reference().typeId());
        }
        out.writeInt(referenced.size());
        for (var typeId : referenced) {
            text(out, typeId);
            var type = context.runtimeSnapshot().types().get(typeId);
            registerFacts(out, type == null ? null : type.register());
        }
    }

    private static void registerFacts(DataOutputStream out, RuntimeDesignSnapshot.RegisterFacts facts) throws IOException {
        out.writeBoolean(facts != null);
        if (facts == null) return;
        text(out, facts.typeId()); text(out, facts.entityName()); text(out, facts.displayName());
        text(out, facts.period()); text(out, facts.periodicity()); text(out, facts.periodColumn());
        registerRoles(out, facts.dimensions()); registerRoles(out, facts.resources());
        stringsOrdered(out, facts.dimensionGroupColumns());
        stringsOrdered(out, facts.rowKeyColumns());
        stringsOrdered(out, facts.winnerOrderFields());
        stringsOrdered(out, facts.recorderTypeCodes());
        out.writeBoolean(facts.recorded()); out.writeBoolean(facts.genericWritesAllowed());
        out.writeBoolean(facts.keyChangeAllowed()); out.writeBoolean(facts.keyWriteAllowed());
    }

    private static void registerRoles(DataOutputStream out, List<RuntimeDesignSnapshot.RegisterFacts.Role> roles) throws IOException {
        out.writeInt(roles.size());
        for (var role : roles) {
            text(out, role.propertyId()); text(out, role.name()); text(out, role.javaType());
            text(out, role.column()); text(out, role.mapping().name());
        }
    }

    private static void draftText(DataOutputStream out, DraftText value) throws IOException {
        text(out, value.mode().name());
        text(out, value.text());
    }

    private static void recipe(DataOutputStream out, RecipeRef recipe) throws IOException {
        text(out, recipe.id());
        out.writeInt(recipe.version());
    }

    private static void id(DataOutputStream out, UUID value) throws IOException {
        if (value == null) {
            out.writeBoolean(false);
            return;
        }
        out.writeBoolean(true);
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeBoolean(false);
            return;
        }
        out.writeBoolean(true);
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void stringsOrdered(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (var value : values) text(out, value);
    }

    private static void stringsSorted(DataOutputStream out, Iterable<String> values) throws IOException {
        var sorted = new ArrayList<String>();
        values.forEach(sorted::add);
        sorted.sort(Comparator.naturalOrder());
        out.writeInt(sorted.size());
        for (var value : sorted) text(out, value);
    }

    private static List<String> sorted(Map<String, ?> values) {
        var keys = new ArrayList<>(values.keySet());
        keys.sort(Comparator.naturalOrder());
        return keys;
    }
}
