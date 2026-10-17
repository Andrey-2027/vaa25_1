package org.ipro.crud;

import org.ipro.data.DataOperation;
import org.ipro.identity.IdentifiableEntity;

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
    private final List<AggregateSaveContributionParticipant> contributionParticipants;
    private final AggregateOperationAuthorizer operationAuthorizer;

    public MetadataDrivenAggregateSaveService(
            ServiceLocator serviceLocator,
            SectionMetadataRegistry sectionRegistry,
            GenericOwnedSectionService sectionService,
            EntityEventPublisher eventPublisher) {
        this(serviceLocator, sectionRegistry, sectionService, eventPublisher,
            new EntityLifecycleRegistry(List.of()), List.of());
    }

    public MetadataDrivenAggregateSaveService(
            ServiceLocator serviceLocator,
            SectionMetadataRegistry sectionRegistry,
            GenericOwnedSectionService sectionService,
            EntityEventPublisher eventPublisher,
            EntityLifecycleRegistry lifecycleRegistry) {
        this(serviceLocator, sectionRegistry, sectionService, eventPublisher, lifecycleRegistry, List.of());
    }

    public MetadataDrivenAggregateSaveService(
            ServiceLocator serviceLocator,
            SectionMetadataRegistry sectionRegistry,
            GenericOwnedSectionService sectionService,
            EntityEventPublisher eventPublisher,
            EntityLifecycleRegistry lifecycleRegistry,
            List<AggregateSaveContributionParticipant> contributionParticipants) {
        this(serviceLocator, sectionRegistry, sectionService, eventPublisher, lifecycleRegistry,
            contributionParticipants, null);
    }

    /**
     * Полный контур: к прежним owners добавлен preflight операции над корнем.
     *
     * <p>Авторизатор опционален не как ослабление права, а как место отказа: canonical save
     * внутри агрегата проверяет то же решение сам. Без авторизатора отказ придёт позже —
     * после aggregate-событий и before-хуков, — поэтому полный контекст передаёт его.
     */
    public MetadataDrivenAggregateSaveService(
            ServiceLocator serviceLocator,
            SectionMetadataRegistry sectionRegistry,
            GenericOwnedSectionService sectionService,
            EntityEventPublisher eventPublisher,
            EntityLifecycleRegistry lifecycleRegistry,
            List<AggregateSaveContributionParticipant> contributionParticipants,
            AggregateOperationAuthorizer operationAuthorizer) {
        this.operationAuthorizer = operationAuthorizer;
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
        this.contributionParticipants = List.copyOf(Objects.requireNonNull(contributionParticipants,
            "contributionParticipants must not be null"));
    }

    /**
     * Сохранить root и фактически подключённые owned sections одним commit.
     * Список {@code attachedSections} не должен содержать отсутствующие секции:
     * отсутствие выражается отсутствием записи в списке.
     */
    @Transactional(rollbackOn = Exception.class)
    public <P extends IdentifiableEntity<Long>> AggregateSaveResult<P> save(
            P aggregate,
            List<SectionInput> attachedSections,
            EventSource source) {
        return save(aggregate, attachedSections, List.of(), source);
    }

    /** Save root, attached sections, and explicitly supplied server-side contributions atomically. */
    @Transactional(rollbackOn = Exception.class)
    public <P extends IdentifiableEntity<Long>> AggregateSaveResult<P> save(
            P aggregate,
            List<SectionInput> attachedSections,
            List<? extends AggregateSaveContribution> contributions,
            EventSource source) {
        Objects.requireNonNull(aggregate, "aggregate must not be null");
        Objects.requireNonNull(attachedSections, "attachedSections must not be null");
        Objects.requireNonNull(contributions, "contributions must not be null");
        Objects.requireNonNull(source, "source must not be null");

        Class<P> aggregateClass = entityType(aggregate);
        Map<Class<?>, TableSectionMetadataInfo> descriptors = descriptorsFor(aggregateClass);
        List<ResolvedInput> inputs = resolveInputs(aggregateClass, attachedSections, descriptors);
        List<ResolvedContribution> resolvedContributions = resolveContributions(aggregateClass, contributions);
        boolean creating = aggregate.getId() == null;
        // Security preflight агрегата — до aggregate-событий, before-хуков и до захвата
        // rollback-состояния: запрещённое сохранение не должно вызывать пользовательский код
        // и не должно публиковать Saving. Строки owned-секций наследуют допуск корня.
        if (operationAuthorizer != null) {
            operationAuthorizer.requireOperation(aggregateClass,
                creating ? DataOperation.CREATE : DataOperation.UPDATE);
        }
        Long originalVersion = aggregate instanceof BaseEntity base && base.getVersion() != null
            ? base.getVersion() : (creating ? null : 0L);

        AggregateSaveRollbackState rollbackState = AggregateSaveRollbackState.current();
        rollbackState.capture(aggregate, null);
        for (ResolvedInput input : inputs) {
            for (Object row : input.input().rows()) {
                rollbackState.capture(AggregateSaveRollbackState.requireLongIdentity(row), input.descriptor());
            }
        }
        for (ResolvedContribution contribution : resolvedContributions) {
            contribution.contribution().rollbackEntities()
                .forEach(entity -> rollbackState.capture(entity, null));
        }

        List<Class<?>> attachedTypes = new ArrayList<>(inputs.size());
        for (ResolvedInput input : inputs) {
            attachedTypes.add(input.input().rowType());
        }
        List<AggregateSection> eventSections = new ArrayList<>(inputs.stream()
            .map(input -> new AggregateSection(input.input().rowType(), input.input().rows()))
            .toList());
        for (ResolvedContribution contribution : resolvedContributions) {
            for (AggregateSection section : contribution.contribution().eventSections()) {
                attachedTypes.add(section.rowType());
                eventSections.add(section);
            }
        }
        EventContext context = EventContext.builder(aggregateClass)
            .source(source)
            .aggregateId(aggregate.getId())
            .attachedSections(attachedTypes)
            .operationName("save:" + aggregateClass.getSimpleName())
            .build();
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
                // ADR-0026: обновление возвращает merge-копию, и onSave увидит её, а не экземпляр
                // команды. Копию к объявленному payload'у привязывает платформа — единственная
                // сторона, которая знает оба экземпляра; приложению сравнивать id и Hibernate-класс
                // не нужно.
                AggregateCommandPayload.bindManagedCopy(aggregate, saved);
                List<SectionResult> persistedSections = new ArrayList<>(inputs.size());
                for (ResolvedInput input : inputs) {
                    replaceRows(saved, input);
                    persistedSections.add(loadPersistedRows(saved, input));
                }
                boolean versionAdvanceRequested = !creating
                    && (!inputs.isEmpty() || originalVersion.longValue() != currentVersion(saved));
                AggregateSaveParticipantContext participantContext =
                    new AggregateSaveParticipantContext(creating, originalVersion,
                        versionAdvanceRequested);
                for (ResolvedContribution contribution : resolvedContributions) {
                    contribution.participant().apply(saved, contribution.contribution(), participantContext);
                }

                EventContext persistedContext = context.withAggregateId(saved.getId());
                eventPublisher.publishSaved(saved, persistedContext);
                eventPublisher.publishChanged(saved, persistedContext);
                try (AggregateSaveLifecycleScope savedScope = AggregateSaveLifecycleScope.open(
                        saved, persistedContext, participantContext)) {
                    lifecycleRegistry.onSave(aggregateClass, saved, persistedContext);
                }
                return new AggregateSaveResult<>(saved, persistedSections);
            }
        }
    }

    private List<ResolvedContribution> resolveContributions(
            Class<?> aggregateClass,
            List<? extends AggregateSaveContribution> contributions) {
        Set<String> seen = new HashSet<>();
        List<ResolvedContribution> result = new ArrayList<>(contributions.size());
        for (AggregateSaveContribution contribution : contributions) {
            Objects.requireNonNull(contribution, "contributions cannot contain null");
            String key = Objects.requireNonNull(contribution.key(), "contribution key must not be null");
            if (key.isBlank() || !seen.add(key)) {
                throw new IllegalArgumentException("Aggregate contribution key is blank or repeated: " + key);
            }
            List<AggregateSaveContributionParticipant> matches = contributionParticipants.stream()
                .filter(participant -> participant.supports(aggregateClass, contribution))
                .toList();
            if (matches.size() != 1) {
                throw new IllegalArgumentException("Aggregate contribution " + key + " for "
                    + aggregateClass.getName() + " requires exactly one server-side participant; found "
                    + matches.size());
            }
            result.add(new ResolvedContribution(contribution, matches.getFirst()));
        }
        return List.copyOf(result);
    }

    private <P extends IdentifiableEntity<Long>> Map<Class<?>, TableSectionMetadataInfo> descriptorsFor(
            Class<P> aggregateClass) {
        Map<Class<?>, TableSectionMetadataInfo> result = new LinkedHashMap<>();
        for (TableSectionMetadataInfo descriptor : sectionRegistry.forOwner(aggregateClass)) {
            result.put(descriptor.getRowClass(), descriptor);
        }
        return result;
    }

    private <P extends IdentifiableEntity<Long>> List<ResolvedInput> resolveInputs(
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

    private <P extends IdentifiableEntity<Long>> List<String> validateSections(
            P aggregate, List<ResolvedInput> inputs) {
        List<String> errors = new ArrayList<>();
        for (ResolvedInput input : inputs) {
            errors.addAll(validateRows(aggregate, input));
        }
        return errors;
    }

    private <P extends IdentifiableEntity<Long>> List<String> validateRows(
            P aggregate, ResolvedInput input) {
        return sectionService.validateRows(
            aggregate, input.typedRows(), input.descriptor());
    }

    private <P extends IdentifiableEntity<Long>> P saveAggregate(
            P aggregate, Class<P> aggregateClass) {
        BaseService<P, Long> service = serviceLocator.findService(aggregateClass);
        return service.save(aggregate);
    }

    private <P extends IdentifiableEntity<Long>> void replaceRows(
            P saved, ResolvedInput input) {
        sectionService.replaceAll(
            saved, input.typedRows(), input.descriptor());
    }

    private <P extends IdentifiableEntity<Long>> SectionResult loadPersistedRows(
            P saved, ResolvedInput input) {
        List<? extends IdentifiableEntity<Long>> rows = sectionService.findByParent(
            saved, input.descriptor());
        return new SectionResult(input.input().rowType(), rows);
    }

    @SuppressWarnings("unchecked")
    private static <P extends IdentifiableEntity<Long>> Class<P> entityType(P entity) {
        if (entity instanceof HibernateProxy proxy) {
            return (Class<P>) proxy.getHibernateLazyInitializer().getPersistentClass();
        }
        return (Class<P>) entity.getClass();
    }

    private static long currentVersion(IdentifiableEntity<Long> entity) {
        if (entity instanceof BaseEntity base && base.getVersion() != null) {
            return base.getVersion();
        }
        return 0L;
    }

    private record ResolvedInput(SectionInput input, TableSectionMetadataInfo descriptor) {
        private List<? extends IdentifiableEntity<Long>> typedRows() {
            return input.rows().stream().map(input.rowType()::cast).toList();
        }
    }

    private record ResolvedContribution(AggregateSaveContribution contribution,
                                        AggregateSaveContributionParticipant participant) {
    }

    /** Payload одной фактически подключённой секции. */
    public record SectionInput(Class<? extends IdentifiableEntity<Long>> rowType, List<?> rows) {

        public SectionInput {
            Objects.requireNonNull(rowType, "rowType must not be null");
            Objects.requireNonNull(rows, "rows must not be null");
            rows = List.copyOf(rows);
        }

        public static SectionInput attached(
                Class<? extends IdentifiableEntity<Long>> rowType, List<?> rows) {
            return new SectionInput(rowType, rows);
        }
    }

    /** Результат одной секции после persistence, включая присвоенные id/line numbers. */
    public record SectionResult(Class<? extends IdentifiableEntity<Long>> rowType,
                                List<? extends IdentifiableEntity<Long>> rows) {

        public SectionResult {
            Objects.requireNonNull(rowType, "rowType must not be null");
            Objects.requireNonNull(rows, "rows must not be null");
            rows = List.copyOf(rows);
        }
    }

    /** Persisted aggregate и только те sections, которые были attached во входе. */
    public record AggregateSaveResult<P extends IdentifiableEntity<Long>>(
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
