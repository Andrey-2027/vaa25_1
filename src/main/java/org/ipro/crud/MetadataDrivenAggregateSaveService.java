package org.ipro.crud;

import jakarta.transaction.Transactional;
import org.hibernate.proxy.HibernateProxy;
import org.ipro.events.AggregateSection;
import org.ipro.events.EntityEventPublisher;
import org.ipro.events.EventContext;
import org.ipro.events.EventSource;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.core.TelemetryBridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Platform default for an atomic aggregate save.
 *
 * <p>The service deliberately accepts section row types and resolved metadata,
 * not application repositories, commands or use-case classes. Omitted sections
 * are absent and are not touched; an attached empty list is an explicit clear.
 * Header persistence remains delegated to the normal {@link BaseService} path,
 * so numbering, Bean Validation, business hooks and RLS checks are preserved.</p>
 *
 * <p>Aggregate lifecycle events are owned here. The root service sees the active
 * aggregate scope and therefore does not publish duplicate Saved/Changed events.
 * Row services are not invoked: {@link GenericOwnedSectionService} is the only
 * persistence implementation for the resolved section contract. A custom section
 * {@code serviceClass} is UI-only (table, fetch, row copy) and never participates
 * in authoritative validation or persistence here; non-standard persistence
 * semantics require an explicit custom aggregate handler.</p>
 *
 * <p>Шапка и owned sections — один агрегат с общей версией ({@code @Version} шапки):
 * сохранение секций увеличивает её даже без изменения шапки, а состояние,
 * подготовленное по устаревшей версии, завершается optimistic-конфликтом вместо
 * тихой перезаписи. После конфликта форма сохраняет правки (rollback-механизм) —
 * для повторного сохранения требуется перечитать актуальное состояние; подстановка
 * свежей версии в старые данные запрещена.</p>
 */
public class MetadataDrivenAggregateSaveService {

    private final ServiceLocator serviceLocator;
    private final SectionMetadataRegistry sectionRegistry;
    private final GenericOwnedSectionService sectionService;
    private final EntityEventPublisher eventPublisher;
    private final EntityLifecycleRegistry lifecycleRegistry;

    public MetadataDrivenAggregateSaveService(
            ServiceLocator serviceLocator,
            SectionMetadataRegistry sectionRegistry,
            GenericOwnedSectionService sectionService,
            EntityEventPublisher eventPublisher) {
        this(serviceLocator, sectionRegistry, sectionService, eventPublisher,
            new EntityLifecycleRegistry(List.of()));
    }

    public MetadataDrivenAggregateSaveService(
            ServiceLocator serviceLocator,
            SectionMetadataRegistry sectionRegistry,
            GenericOwnedSectionService sectionService,
            EntityEventPublisher eventPublisher,
            EntityLifecycleRegistry lifecycleRegistry) {
        this.serviceLocator = Objects.requireNonNull(serviceLocator,
            "serviceLocator must not be null");
        this.sectionRegistry = Objects.requireNonNull(sectionRegistry,
            "sectionRegistry must not be null");
        this.sectionService = Objects.requireNonNull(sectionService,
            "sectionService must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher,
            "eventPublisher must not be null");
        this.lifecycleRegistry = Objects.requireNonNull(lifecycleRegistry,
            "lifecycleRegistry must not be null");
    }

    /**
     * Сохранить root и фактически подключённые owned sections одним commit.
     * Список {@code attachedSections} не должен содержать отсутствующие секции:
     * отсутствие выражается отсутствием записи в списке.
     */
    @Transactional(rollbackOn = Exception.class)
    public <P extends IdentifiableEntity> AggregateSaveResult<P> save(
            P aggregate,
            List<SectionInput> attachedSections,
            EventSource source) {
        Objects.requireNonNull(aggregate, "aggregate must not be null");
        Objects.requireNonNull(attachedSections, "attachedSections must not be null");
        Objects.requireNonNull(source, "source must not be null");

        Class<P> aggregateClass = entityType(aggregate);
        Map<Class<?>, TableSectionMetadataInfo> descriptors = descriptorsFor(aggregateClass);
        List<ResolvedInput> inputs = resolveInputs(aggregateClass, attachedSections, descriptors);

        AggregateSaveRollbackState rollbackState = AggregateSaveRollbackState.current();
        rollbackState.capture(aggregate, null);
        for (ResolvedInput input : inputs) {
            for (Object row : input.input().rows()) {
                rollbackState.capture((IdentifiableEntity) row, input.descriptor());
            }
        }

        List<Class<?>> attachedTypes = new ArrayList<>(inputs.size());
        for (ResolvedInput input : inputs) {
            attachedTypes.add(input.input().rowType());
        }
        EventContext context = EventContext.builder(aggregateClass)
            .source(source)
            .aggregateId(aggregate.getId())
            .attachedSections(attachedTypes)
            .operationName("save:" + aggregateClass.getSimpleName())
            .build();
        List<AggregateSection> eventSections = inputs.stream()
            .map(input -> new AggregateSection(input.input().rowType(), input.input().rows()))
            .toList();

        try (EntityEventPublisher.EventScope ignored = eventPublisher.openAggregateOperation(context)) {
            eventPublisher.publishAggregateSaving(aggregate, eventSections, context);
            lifecycleRegistry.beforeAggregateSave(
                aggregateClass, aggregate, eventSections, context);

            List<String> errors = validateSections(aggregate, inputs);
            if (!errors.isEmpty()) {
                throw new ValidationException(String.join(System.lineSeparator(), errors));
            }

            try (OperationScope telemetry = TelemetryBridge.beginOperation(
                    "save:" + aggregateClass.getSimpleName())) {
                P saved = saveAggregate(aggregate, aggregateClass);
                List<SectionResult> persistedSections = new ArrayList<>(inputs.size());
                for (ResolvedInput input : inputs) {
                    replaceRows(saved, input);
                    persistedSections.add(loadPersistedRows(saved, input));
                }

                EventContext persistedContext = context.withAggregateId(saved.getId());
                eventPublisher.publishSaved(saved, persistedContext);
                eventPublisher.publishChanged(saved, persistedContext);
                lifecycleRegistry.onSave(aggregateClass, saved, persistedContext);
                return new AggregateSaveResult<>(saved, persistedSections);
            }
        }
    }

    private <P extends IdentifiableEntity> Map<Class<?>, TableSectionMetadataInfo> descriptorsFor(
            Class<P> aggregateClass) {
        Map<Class<?>, TableSectionMetadataInfo> result = new LinkedHashMap<>();
        for (TableSectionMetadataInfo descriptor : sectionRegistry.forOwner(aggregateClass)) {
            result.put(descriptor.getRowClass(), descriptor);
        }
        return result;
    }

    private <P extends IdentifiableEntity> List<ResolvedInput> resolveInputs(
            Class<P> aggregateClass,
            List<SectionInput> attachedSections,
            Map<Class<?>, TableSectionMetadataInfo> descriptors) {
        Set<Class<?>> seen = new HashSet<>();
        List<ResolvedInput> result = new ArrayList<>(attachedSections.size());
        for (SectionInput input : attachedSections) {
            if (!seen.add(input.rowType())) {
                throw new ValidationException("Секция " + input.rowType().getName()
                    + " подключена более одного раза для " + aggregateClass.getName());
            }
            TableSectionMetadataInfo descriptor = descriptors.get(input.rowType());
            if (descriptor == null) {
                throw new IllegalArgumentException("Секция " + input.rowType().getName()
                    + " не объявлена владельцем " + aggregateClass.getName());
            }
            if (input.rows().stream().anyMatch(row -> row != null
                    && !descriptor.getRowClass().isInstance(row))) {
                throw new ValidationException("Секция " + descriptor.getKey()
                    + " содержит строку неподходящего типа");
            }
            result.add(new ResolvedInput(input, descriptor));
        }
        return List.copyOf(result);
    }

    private <P extends IdentifiableEntity> List<String> validateSections(
            P aggregate, List<ResolvedInput> inputs) {
        List<String> errors = new ArrayList<>();
        for (ResolvedInput input : inputs) {
            errors.addAll(validateRows(aggregate, input));
        }
        return errors;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <P extends IdentifiableEntity> List<String> validateRows(
            P aggregate, ResolvedInput input) {
        return sectionService.validateRows(
            aggregate, (List) input.input().rows(), input.descriptor());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <P extends IdentifiableEntity> P saveAggregate(
            P aggregate, Class<P> aggregateClass) {
        BaseService service = serviceLocator.findService(aggregateClass);
        return (P) service.save(aggregate);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <P extends IdentifiableEntity> void replaceRows(
            P saved, ResolvedInput input) {
        sectionService.replaceAll(
            saved, (List) input.input().rows(), input.descriptor());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <P extends IdentifiableEntity> SectionResult loadPersistedRows(
            P saved, ResolvedInput input) {
        List<? extends IdentifiableEntity> rows = sectionService.findByParent(
            saved, input.descriptor());
        return new SectionResult(input.input().rowType(), rows);
    }

    @SuppressWarnings("unchecked")
    private static <P extends IdentifiableEntity> Class<P> entityType(P entity) {
        if (entity instanceof HibernateProxy proxy) {
            return (Class<P>) proxy.getHibernateLazyInitializer().getPersistentClass();
        }
        return (Class<P>) entity.getClass();
    }

    private record ResolvedInput(SectionInput input, TableSectionMetadataInfo descriptor) {
    }

    /** Payload одной фактически подключённой секции. */
    public record SectionInput(Class<? extends IdentifiableEntity> rowType, List<?> rows) {

        public SectionInput {
            Objects.requireNonNull(rowType, "rowType must not be null");
            Objects.requireNonNull(rows, "rows must not be null");
            rows = List.copyOf(rows);
        }

        public static SectionInput attached(
                Class<? extends IdentifiableEntity> rowType, List<?> rows) {
            return new SectionInput(rowType, rows);
        }
    }

    /** Результат одной секции после persistence, включая присвоенные id/line numbers. */
    public record SectionResult(Class<? extends IdentifiableEntity> rowType,
                                List<? extends IdentifiableEntity> rows) {

        public SectionResult {
            Objects.requireNonNull(rowType, "rowType must not be null");
            Objects.requireNonNull(rows, "rows must not be null");
            rows = List.copyOf(rows);
        }
    }

    /** Persisted aggregate и только те sections, которые были attached во входе. */
    public record AggregateSaveResult<P extends IdentifiableEntity>(
            P aggregate,
            List<SectionResult> sections) {

        public AggregateSaveResult {
            Objects.requireNonNull(aggregate, "aggregate must not be null");
            sections = List.copyOf(Objects.requireNonNull(sections, "sections must not be null"));
        }

        public java.util.Optional<SectionResult> section(Class<?> rowType) {
            return sections.stream().filter(section -> section.rowType().equals(rowType)).findFirst();
        }
    }
}
