package org.ipro.metadata;

import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.TableSections;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /** Все диагностики по managed-типам, с применёнными исключениями. */
    public static List<MetadataDiagnostic> validate(MetadataResolver resolver,
                                                    Iterable<Class<?>> managedTypes,
                                                    List<MetadataAllowance> allowances) {
        Set<Class<?>> metadataTypes = new LinkedHashSet<>();
        for (Class<?> type : managedTypes) {
            if (type.isAnnotationPresent(EntityMetadata.class)) {
                metadataTypes.add(type);
                TableSections sections = type.getAnnotation(TableSections.class);
                if (sections != null) {
                    metadataTypes.addAll(List.of(sections.value()));
                }
            }
        }

        Map<Class<?>, List<MetadataDiagnostic>> byType = new LinkedHashMap<>();
        for (Class<?> type : metadataTypes) {
            byType.put(type, fieldDiagnostics(resolver, type, metadataTypes));
        }

        List<MetadataDiagnostic> all = new ArrayList<>();
        byType.values().forEach(all::addAll);
        List<MetadataDiagnostic> withAllowances = applyAllowances(all,
            allowances == null ? List.of() : allowances);

        List<MetadataDiagnostic> sorted = new ArrayList<>(withAllowances);
        sorted.sort(Comparator.comparing(MetadataDiagnostic::entity)
            .thenComparing(MetadataDiagnostic::field)
            .thenComparing(diagnostic -> diagnostic.severity().ordinal())
            .thenComparing(MetadataDiagnostic::code));
        return List.copyOf(sorted);
    }

    private static List<MetadataDiagnostic> fieldDiagnostics(MetadataResolver resolver,
                                                             Class<?> type,
                                                             Set<Class<?>> metadataTypes) {
        List<MetadataDiagnostic> result = new ArrayList<>();
        for (FieldMetadataInfo field : resolver.resolveAllAnnotatedFields(type)) {
            result.addAll(field.getDiagnostics());
            if (field.hasLookup() && !metadataTypes.contains(field.getLookupEntity())) {
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
