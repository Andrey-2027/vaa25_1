package org.ipro.metadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Eager-проверка метаданных на старте приложения (C4.2, ADR-0007 §6).
 *
 * <p>Бин существует ради побочного эффекта: при создании контекста он прогоняет
 * {@link MetadataConsistencyValidator} по всем managed-типам и останавливает старт, если есть
 * хотя бы одна {@code ERROR}. Останавливаться на старте — принципиально: ошибка в контракте
 * поля иначе проявится только при открытии конкретной формы, и обнаружит её пользователь, а
 * не сборка.</p>
 *
 * <p>Сообщение содержит все ошибки сразу — с сущностью, полем и источником факта, — чтобы
 * исправление не шло по одной ошибке за прогон.</p>
 */
public final class MetadataConsistencyStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(MetadataConsistencyStartupCheck.class);

    private final List<MetadataDiagnostic> diagnostics;

    public MetadataConsistencyStartupCheck(ManagedEntityCatalog managedEntityCatalog,
                                           MetadataResolver metadataResolver,
                                           List<MetadataAllowance> allowances) {
        this(managedEntityCatalog.managedEntityClasses(), metadataResolver, allowances);
    }

    /**
     * Проверка по явному набору типов. Eager-контракт не зависит от {@code ManagedEntityCatalog}:
     * ему нужны только типы, поэтому этот вариант позволяет проверить сам fail-fast
     * (остановка старта и полнота сообщения) без поднятия persistence unit.
     */
    MetadataConsistencyStartupCheck(Collection<Class<?>> managedTypes,
                                    MetadataResolver metadataResolver,
                                    List<MetadataAllowance> allowances) {
        Objects.requireNonNull(managedTypes, "managedTypes must not be null");
        Objects.requireNonNull(metadataResolver, "metadataResolver must not be null");

        List<MetadataDiagnostic> validated = MetadataConsistencyValidator.validate(
            metadataResolver, managedTypes,
            allowances == null ? List.of() : allowances);

        List<MetadataDiagnostic> errors = validated.stream()
            .filter(diagnostic -> diagnostic.severity() == MetadataDiagnostic.Severity.ERROR)
            .toList();
        if (!errors.isEmpty()) {
            StringBuilder message = new StringBuilder("Метаданные не согласованы: ")
                .append(errors.size()).append(" ошибок\n");
            errors.forEach(error -> message.append("  ").append(error.render()).append('\n'));
            throw new IllegalStateException(message.toString());
        }

        validated.forEach(diagnostic -> {
            switch (diagnostic.severity()) {
                case WARNING -> log.warn("{}", diagnostic.render());
                case INFO -> log.info("{}", diagnostic.render());
                case ERROR -> { /* недостижимо: ошибки уже остановили старт */ }
            }
        });
        this.diagnostics = List.copyOf(validated);
    }

    /** Диагностики последней проверки — для тестов и диагностических экранов. */
    public List<MetadataDiagnostic> diagnostics() {
        return diagnostics;
    }
}
