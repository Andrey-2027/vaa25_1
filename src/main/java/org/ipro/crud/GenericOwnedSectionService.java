package org.ipro.crud;

import jakarta.persistence.EntityGraph;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.hibernate.proxy.HibernateProxy;
import org.ipro.metadata.FetchGraphs;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.metadata.annotation.SectionRlsPolicy;
import org.ipro.rls.RlsPolicyEnforcer;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generic platform persistence для owned mutable sections.
 *
 * <p>Сервис намеренно принимает resolved descriptor, а не application repository/service.
 * Это позволяет aggregate save engine использовать один путь для любого row class:
 * validation, parent linking, line-number policy, replace-all и delete lifecycle.</p>
 *
 * <p>Lifecycle events и проверка права на aggregate root принадлежат вызывающему
 * aggregate service. Здесь остаётся только persistence section contract.</p>
 */
public class GenericOwnedSectionService {

    @PersistenceContext
    private EntityManager entityManager;

    private final Validator validator;
    private final MetadataResolver metadataResolver;
    private final SectionMetadataRegistry sectionRegistry;
    private final RlsPolicyEnforcer rlsPolicyEnforcer;

    public GenericOwnedSectionService(
            Validator validator,
            MetadataResolver metadataResolver,
            SectionMetadataRegistry sectionRegistry,
            RlsPolicyEnforcer rlsPolicyEnforcer) {
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.metadataResolver = Objects.requireNonNull(metadataResolver,
            "metadataResolver must not be null");
        this.sectionRegistry = Objects.requireNonNull(sectionRegistry,
            "sectionRegistry must not be null");
        this.rlsPolicyEnforcer = Objects.requireNonNull(rlsPolicyEnforcer,
            "rlsPolicyEnforcer must not be null");
    }

    /** Создаёт строку через canonical no-arg constructor и связывает её с root. */
    @Transactional
    public <R extends IdentifiableEntity, P extends IdentifiableEntity> R createNew(
            P parent, TableSectionMetadataInfo descriptor) {
        requireDescriptor(parent, descriptor);
        try {
            @SuppressWarnings("unchecked")
            Class<R> rowClass = (Class<R>) descriptor.getRowClass();
            var constructor = rowClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            R row = constructor.newInstance();
            descriptor.linkToParent(row, parent);
            return row;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create new "
                + descriptor.getRowClass().getName() + " via no-arg constructor", e);
        }
    }

    /**
     * Проверяет минимальное количество строк, owner/type contract и Bean Validation.
     * Значение parent у строки допускается null (оно будет проставлено перед persistence),
     * но уже установленный другой parent считается попыткой перемещения строки.
     */
    public <R extends IdentifiableEntity, P extends IdentifiableEntity> List<String> validateRows(
            P parent, List<R> rows, TableSectionMetadataInfo descriptor) {
        requireDescriptor(parent, descriptor);
        List<String> errors = new ArrayList<>();
        if (rows == null) {
            return List.of("Секция " + descriptor.getTitle() + " не может иметь null rows");
        }
        if (rows.size() < descriptor.getMinRows()) {
            errors.add("Секция «" + descriptor.getTitle() + "» должна содержать минимум "
                + descriptor.getMinRows() + " строк");
        }
        for (int index = 0; index < rows.size(); index++) {
            R row = rows.get(index);
            if (row == null) {
                errors.add("Секция «" + descriptor.getTitle() + "»: строка "
                    + (index + 1) + " не может быть null");
                continue;
            }
            if (!descriptor.getRowClass().isInstance(row)) {
                errors.add("Секция «" + descriptor.getTitle() + "»: строка "
                    + (index + 1) + " имеет тип " + row.getClass().getName());
                continue;
            }
            Object currentParent = readParent(row, descriptor);
            if (currentParent != null && !sameEntity(currentParent, parent)) {
                errors.add("Секция «" + descriptor.getTitle() + "»: строка "
                    + (index + 1) + " принадлежит другому документу");
            } else if (currentParent == null) {
                // UI/new-row payloads may omit the back-reference; the platform owns
                // this mechanical link and makes Bean Validation see the final state.
                descriptor.linkToParent(row, parent);
            }
            for (ConstraintViolation<R> violation : validator.validate(row)) {
                errors.add("Строка " + (index + 1) + ": " + violation.getMessage());
            }
        }
        return List.copyOf(errors);
    }

    /**
     * Полностью приводит состояние одной owned-секции к переданному списку.
     * New rows persist, existing rows merge, отсутствующие строки удаляются.
     */
    @Transactional
    public <R extends IdentifiableEntity, P extends IdentifiableEntity> void replaceAll(
            P parent, List<R> rows, TableSectionMetadataInfo descriptor) {
        requireDescriptor(parent, descriptor);
        rlsPolicyEnforcer.requireUpdate(parent);
        if (parent.getId() == null) {
            throw new IllegalArgumentException("Cannot replace section " + descriptor.getKey()
                + " for transient owner " + parent.getClass().getName());
        }
        if (rows == null) {
            throw new ValidationException("Секция «" + descriptor.getTitle()
                + "» не может иметь null rows");
        }
        List<R> requested = rows;
        prepareRows(parent, requested, descriptor);
        List<String> errors = new ArrayList<>(validateRows(parent, requested, descriptor));
        if (!errors.isEmpty()) {
            throw new ValidationException(String.join(System.lineSeparator(), errors));
        }

        List<R> existingRows = findByParent(parent, descriptor);
        Map<Long, R> existingById = new HashMap<>();
        for (R existing : existingRows) {
            existingById.put(existing.getId(), existing);
        }
        Set<Long> requestedIds = new HashSet<>();
        for (R row : requested) {
            Long id = row.getId();
            if (id == null) {
                continue;
            }
            if (!requestedIds.add(id)) {
                throw new ValidationException("Секция «" + descriptor.getTitle()
                    + "» содержит строку с повторяющимся id=" + id);
            }
            if (!existingById.containsKey(id)) {
                throw new ValidationException("Строка id=" + id + " не принадлежит секции "
                    + descriptor.getKey());
            }
        }

        // Сначала удаляем строки, которых нет в новом состоянии, и только затем вставляем
        // новые. Hibernate выполняет INSERT раньше DELETE, поэтому обратный порядок ломался
        // бы на секции с уникальным ключом (например, номенклатура + тип атрибута), когда
        // в одном сохранении строку удаляют и заводят заново с тем же ключом.
        boolean removed = false;
        for (R existing : existingRows) {
            if (!requestedIds.contains(existing.getId())) {
                entityManager.remove(existing);
                removed = true;
            }
        }
        if (removed) {
            entityManager.flush();
        }

        for (R row : requested) {
            if (row.getId() == null) {
                entityManager.persist(row);
            } else {
                entityManager.merge(row);
            }
        }
        entityManager.flush();
    }

    /** Загружает строки в line-number order с metadata-derived fetch graph. */
    @Transactional(readOnly = true)
    public <R extends IdentifiableEntity, P extends IdentifiableEntity> List<R> findByParent(
            P parent, TableSectionMetadataInfo descriptor) {
        List<String> paths = FetchGraphs.entityReferencePaths(descriptor.getGridFields());
        return findByParent(parent, descriptor, paths);
    }

    /** Загружает строки с явно запрошенным UI fetch graph. */
    @Transactional(readOnly = true)
    public <R extends IdentifiableEntity, P extends IdentifiableEntity> List<R> findByParent(
            P parent,
            TableSectionMetadataInfo descriptor,
            java.util.Collection<String> fetchPaths) {
        requireDescriptor(parent, descriptor);
        Objects.requireNonNull(fetchPaths, "fetchPaths must not be null");
        if (!rlsPolicyEnforcer.prepareRead(descriptor.getOwnerClass(), entityManager)) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        Class<R> rowClass = (Class<R>) descriptor.getRowClass();
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<R> query = cb.createQuery(rowClass);
        Root<R> root = query.from(rowClass);
        query.select(root).where(cb.equal(root.get(descriptor.getParentFieldName()), parent));
        if (descriptor.hasLineNumberField()) {
            query.orderBy(cb.asc(root.get(descriptor.getLineNumberFieldName())));
        }
        TypedQuery<R> typedQuery = entityManager.createQuery(query);
        EntityGraph<R> graph = FetchGraphs.fromPaths(entityManager, rowClass,
            FetchGraphs.deepen(rowClass, fetchPaths, metadataResolver));
        if (graph != null) {
            typedQuery.setHint("jakarta.persistence.fetchgraph", graph);
        }
        return typedQuery.getResultList();
    }

    /** Удаляет все строки root независимо от их текущего состава в UI. */
    @Transactional
    public <R extends IdentifiableEntity, P extends IdentifiableEntity> void deleteAll(
            P parent, TableSectionMetadataInfo descriptor) {
        requireDescriptor(parent, descriptor);
        rlsPolicyEnforcer.requireDelete(parent);
        for (R row : this.<R, P>findByParent(parent, descriptor)) {
            entityManager.remove(row);
        }
        entityManager.flush();
    }

    /** Удаляет все standard owned sections, явно объявленные aggregate root. */
    @Transactional
    public <P extends IdentifiableEntity> void deleteAllOwnedSections(P parent) {
        Objects.requireNonNull(parent, "parent must not be null");
        rlsPolicyEnforcer.requireDelete(parent);
        for (TableSectionMetadataInfo descriptor : sectionRegistry.forOwner(entityType(parent))) {
            deleteAll(parent, descriptor);
        }
    }

    private <R extends IdentifiableEntity, P extends IdentifiableEntity> void prepareRows(
            P parent, List<R> rows, TableSectionMetadataInfo descriptor) {
        for (int index = 0; index < rows.size(); index++) {
            R row = rows.get(index);
            if (row == null || !descriptor.getRowClass().isInstance(row)) {
                continue;
            }
            Object currentParent = readParent(row, descriptor);
            if (currentParent != null && !sameEntity(currentParent, parent)) continue;
            if (currentParent == null) {
                descriptor.linkToParent(row, parent);
            }
            if (descriptor.hasLineNumberField()) {
                descriptor.setLineNumber(row, index + 1);
            }
        }
    }

    private Object readParent(Object row, TableSectionMetadataInfo descriptor) {
        try {
            return descriptor.getParentField().get(row);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read parent field " + descriptor.getKey(), e);
        }
    }

    /** Сравнивает entity и detached Hibernate proxy без попытки инициализировать proxy. */
    private static boolean sameEntity(Object first, Object second) {
        if (first == second) {
            return true;
        }
        if (first instanceof IdentifiableEntity firstEntity
                && second instanceof IdentifiableEntity secondEntity) {
            Long firstId = firstEntity.getId();
            Long secondId = secondEntity.getId();
            if (firstId != null && secondId != null) {
                return firstId.equals(secondId)
                    && entityType(first).equals(entityType(second));
            }
        }
        return Objects.equals(first, second);
    }

    private static Class<?> entityType(Object entity) {
        if (entity instanceof HibernateProxy proxy) {
            return proxy.getHibernateLazyInitializer().getPersistentClass();
        }
        return entity.getClass();
    }

    private void requireDescriptor(IdentifiableEntity parent, TableSectionMetadataInfo descriptor) {
        Objects.requireNonNull(parent, "parent must not be null");
        Objects.requireNonNull(descriptor, "descriptor must not be null");
        if (!descriptor.getOwnerClass().isInstance(parent)) {
            throw new IllegalArgumentException("Section " + descriptor.getKey()
                + " belongs to owner " + descriptor.getOwnerClass().getName()
                + ", but got " + parent.getClass().getName());
        }
        if (descriptor.getPersistenceMode()
                != org.ipro.metadata.annotation.SectionPersistenceMode.MUTABLE_REPLACE_ALL) {
            throw new UnsupportedOperationException("Unsupported persistence mode for "
                + descriptor.getKey() + ": " + descriptor.getPersistenceMode());
        }
        if (descriptor.getRlsPolicy() != SectionRlsPolicy.INHERIT_ROOT) {
            throw new UnsupportedOperationException("Unsupported RLS policy for section "
                + descriptor.getKey() + ": " + descriptor.getRlsPolicy()
                + "; standalone sections require a dedicated policy boundary");
        }
        // Descriptors created by an explicitly supplied MetadataResolver are still valid;
        // registry membership is checked by semantic identity, not object identity.
        if (sectionRegistry.forOwner(descriptor.getOwnerClass()).stream()
                .noneMatch(candidate -> candidate.getRowClass().equals(descriptor.getRowClass()))) {
            throw new IllegalArgumentException("Section descriptor is not registered: "
                + descriptor.getKey());
        }
    }
}
