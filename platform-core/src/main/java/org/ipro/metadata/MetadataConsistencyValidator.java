package org.ipro.metadata;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.TableSections;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Сквозная проверка метаданных всех сущностей (C4.2, ADR-0007 §6).
 *
 * <p>До C4.2 вывод {@code required}/{@code type}/{@code reference} проверялся только при
 * первом открытии конкретной формы: противоречие в сущности, которую в этом прогоне не
 * открывали, оставалось незамеченным. Проверка обходит все metadata-driven типы и строки их
 * секций сразу — старт приложения, а не первый клик.</p>
 *
 * <p>Проверка не бросает исключение: она возвращает {@link MetadataDiagnostic}. Решение
 * «останавливать старт» принимает {@link MetadataConsistencyStartupCheck}, который видит
 * весь список и сообщает все ошибки сразу, а не первую.</p>
 */
public final class MetadataConsistencyValidator {

    private MetadataConsistencyValidator() {
    }

    /** All diagnostics from the shared persistence-unit snapshot, with applicable allowances. */
    public static List<MetadataDiagnostic> validate(MetadataResolver resolver,
                                                    ManagedEntityTypes managedEntityTypes,
                                                    List<MetadataAllowance> allowances) {
        return validate(resolver,
            Objects.requireNonNull(managedEntityTypes, "managedEntityTypes must not be null").all(),
            allowances);
    }

    /** Combines validated facts from upper layers before applying the shared allowance policy once. */
    public static List<MetadataDiagnostic> validate(MetadataResolver resolver, ManagedEntityTypes types,
            List<MetadataAllowance> allowances, List<MetadataDiagnostic> additionalDiagnostics) {
        List<MetadataDiagnostic> all = new ArrayList<>(validate(resolver, types, List.of()));
        all.addAll(List.copyOf(additionalDiagnostics));
        return finish(all, allowances);
    }

    private static boolean publishedLookup(FieldMetadataInfo field) {
        return field.hasLookup() && (!field.isHidden() || field.isGridVisible());
    }

    /** Все диагностики по managed-типам, с применёнными исключениями. */
    public static List<MetadataDiagnostic> validate(MetadataResolver resolver,
                                                    Iterable<Class<?>> managedTypes,
                                                    List<MetadataAllowance> allowances) {
        Set<Class<?>> metadataTypes = new LinkedHashSet<>();
        for (Class<?> type : managedTypes) {
            if (MetadataResolver.isApplicable(type)) {
                metadataTypes.add(type);
                TableSections sections = type.getAnnotation(TableSections.class);
                if (sections != null) {
                    metadataTypes.addAll(List.of(sections.value()));
                }
            }
        }

        CatalogOwnerResolver catalogOwners = new CatalogOwnerResolver();
        Map<Class<?>, List<MetadataDiagnostic>> byType = new LinkedHashMap<>();
        for (Class<?> type : metadataTypes) {
            List<MetadataDiagnostic> diagnostics = new ArrayList<>();
            diagnostics.addAll(legacyFormSettings(type));
            diagnostics.addAll(catalogOwners.resolve(type).diagnostics());
            try {
                // Resolve root-level declarations too (kind, columns and displaySortFields).
                resolver.find(type);
                diagnostics.addAll(resolver.getFormColumnDeclarations().diagnostics(type));
            } catch (IllegalArgumentException invalidDeclaration) {
                diagnostics.add(new MetadataDiagnostic(MetadataDiagnostic.Severity.ERROR,
                    MetadataDiagnosticCodes.INVALID_ENTITY_METADATA, type.getName(), "", "@EntityMetadata",
                    invalidDeclaration.getMessage()));
            }
            try {
                diagnostics.addAll(fieldDiagnostics(resolver, type, metadataTypes));
            } catch (IllegalArgumentException invalidField) {
                diagnostics.add(new MetadataDiagnostic(MetadataDiagnostic.Severity.ERROR,
                    MetadataDiagnosticCodes.INVALID_FIELD_METADATA, type.getName(), "", "@FieldMetadata",
                    invalidField.getMessage()));
            }
            byType.put(type, List.copyOf(diagnostics));
        }

        List<MetadataDiagnostic> all = new ArrayList<>();
        byType.values().forEach(all::addAll);
        return finish(all, allowances);
    }

    private static List<MetadataDiagnostic> finish(List<MetadataDiagnostic> all, List<MetadataAllowance> allowances) {
        List<MetadataDiagnostic> withAllowances = applyAllowances(all,
            allowances == null ? List.of() : allowances);

        List<MetadataDiagnostic> sorted = new ArrayList<>(withAllowances);
        sorted.sort(Comparator.comparing(MetadataDiagnostic::entity)
            .thenComparing(MetadataDiagnostic::field)
            .thenComparing(diagnostic -> diagnostic.severity().ordinal())
            .thenComparing(MetadataDiagnostic::code));
        return List.copyOf(sorted);
    }

    /** Read explicit annotation pairs, including defaults; reflection loses explicit empty/false values. */
    private static List<MetadataDiagnostic> legacyFormSettings(Class<?> type) {
        var result = new ArrayList<MetadataDiagnostic>();
        for (Class<?> declaring = type; declaring != null && declaring != Object.class; declaring = declaring.getSuperclass()) {
            boolean strict = declaring.isAnnotationPresent(org.ipro.metadata.annotation.FormSettingsOnly.class);
            String resource = "/" + declaring.getName().replace('.', '/') + ".class";
            try (var bytes = declaring.getResourceAsStream(resource)) {
                if (bytes == null) throw new java.io.IOException("Class bytes unavailable: " + resource);
                new org.springframework.asm.ClassReader(bytes).accept(new org.springframework.asm.ClassVisitor(org.springframework.asm.Opcodes.ASM9) {
                    private org.springframework.asm.AnnotationVisitor annotation(String descriptor, String field) {
                        String prefix = "Lorg/ipro/metadata/annotation/";
                        if (!descriptor.startsWith(prefix)) return null;
                        String name = descriptor.substring(prefix.length(), descriptor.length() - 1);
                        if (name.equals("GridColumn")) report(field, "@GridColumn");
                        return new org.springframework.asm.AnnotationVisitor(org.springframework.asm.Opcodes.ASM9) {
                            private void pair(String key) {
                                if (name.equals("EntityMetadata") && Set.of("listColumns", "selectColumns").contains(key)
                                        || name.equals("FieldMetadata") && Set.of("grid", "readOnly").contains(key)
                                        || name.equals("Lookup") && Set.of("entity", "variant", "fetch").contains(key))
                                    report(field, "@" + name + "." + key);
                            }
                            @Override public void visit(String key, Object value) { pair(key); }
                            @Override public org.springframework.asm.AnnotationVisitor visitArray(String key) { pair(key); return null; }
                            @Override public org.springframework.asm.AnnotationVisitor visitAnnotation(String key, String nested) {
                                pair(key);
                                // grid is already reported as FieldMetadata.grid, once per declaration.
                                return key.equals("grid") ? null : annotation(nested, field);
                            }
                        };
                    }
                    private void report(String field, String source) {
                        result.add(new MetadataDiagnostic(strict ? MetadataDiagnostic.Severity.ERROR : MetadataDiagnostic.Severity.WARNING,
                            strict ? MetadataDiagnosticCodes.FORBIDDEN_FORM_SETTING : MetadataDiagnosticCodes.LEGACY_FORM_SETTING,
                            type.getName(), field, source,
                            strict ? "Устаревшая настройка запрещена для @FormSettingsOnly; перенесите её в конфигурацию формы"
                                : "Настройка читается для совместимости старого кода; новые объявления задавайте в форме"));
                    }
                    @Override public org.springframework.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { return annotation(descriptor, ""); }
                    @Override public org.springframework.asm.FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                        return new org.springframework.asm.FieldVisitor(org.springframework.asm.Opcodes.ASM9) {
                            @Override public org.springframework.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { return annotation(descriptor, name); }
                        };
                    }
                }, org.springframework.asm.ClassReader.SKIP_CODE | org.springframework.asm.ClassReader.SKIP_DEBUG | org.springframework.asm.ClassReader.SKIP_FRAMES);
            } catch (java.io.IOException | RuntimeException failure) {
                result.add(new MetadataDiagnostic(MetadataDiagnostic.Severity.ERROR, MetadataDiagnosticCodes.INVALID_ENTITY_METADATA,
                    type.getName(), "", "form settings policy", "Не удалось проверить объявления " + declaring.getName() + ": " + failure));
            }
        }
        return List.copyOf(result);
    }

    private static List<MetadataDiagnostic> fieldDiagnostics(MetadataResolver resolver,
                                                             Class<?> type,
                                                             Set<Class<?>> metadataTypes) {
        List<MetadataDiagnostic> result = new ArrayList<>();
        for (FieldMetadataInfo field : resolver.resolveAllAnnotatedFields(type)) {
            result.addAll(field.getDiagnostics());
            if (publishedLookup(field) && !metadataTypes.contains(field.getLookupEntity())) {
                result.add(new MetadataDiagnostic(MetadataDiagnostic.Severity.ERROR,
                    MetadataDiagnosticCodes.REFERENCE_TARGET_NOT_METADATA,
                    type.getName(), field.getName(),
                    "@Lookup / тип ссылки",
                    "цель выбора " + field.getLookupEntity().getName()
                        + " не объявлена как metadata-driven: форма выбора для неё не строится"));
            }
        }
        return result;
    }

    /**
     * Понижает разрешённые диагностики до {@code INFO} с причиной и сообщает об исключении,
     * которое больше ни на что не опирается.
     */
    private static List<MetadataDiagnostic> applyAllowances(List<MetadataDiagnostic> diagnostics,
                                                            List<MetadataAllowance> allowances) {
        List<MetadataDiagnostic> result = new ArrayList<>(diagnostics);
        for (MetadataAllowance allowance : allowances) {
            if (!allowance.isAllowable()) {
                // Конфликт контракта поля не гасится договорённостью: исключение вне
                // перечня само становится ошибкой старта, а исходная диагностика остаётся.
                result.add(new MetadataDiagnostic(MetadataDiagnostic.Severity.ERROR,
                    MetadataDiagnosticCodes.DISALLOWED_ALLOWANCE, allowance.entity(),
                    allowance.field(), "MetadataAllowance",
                    "исключение для " + allowance.code() + " не разрешено: погашаются только "
                        + MetadataAllowance.ALLOWABLE_CODES
                        + "; конфликты контракта поля остаются ошибками старта"
                        + " (причина в исключении: " + allowance.reason() + ")"));
                continue;
            }
            boolean matched = false;
            for (int i = 0; i < result.size(); i++) {
                MetadataDiagnostic diagnostic = result.get(i);
                if (!diagnostic.entity().equals(allowance.entity())
                        || !diagnostic.field().equals(allowance.field())
                        || !diagnostic.code().equals(allowance.code())) {
                    continue;
                }
                matched = true;
                result.set(i, new MetadataDiagnostic(MetadataDiagnostic.Severity.INFO,
                    diagnostic.code(), diagnostic.entity(), diagnostic.field(),
                    diagnostic.source() + " (разрешено: " + allowance.reason() + ")",
                    diagnostic.message()));
            }
            if (!matched) {
                result.add(new MetadataDiagnostic(MetadataDiagnostic.Severity.ERROR,
                    MetadataDiagnosticCodes.STALE_ALLOWANCE, allowance.entity(),
                    allowance.field(), "MetadataAllowance",
                    "исключение для " + allowance.code() + " объявлено, но условия больше нет"
                        + " (причина в исключении: " + allowance.reason() + ")"));
            }
        }
        return result;
    }
}
