package org.ipro.form;

import org.ipro.identity.IdentifiableEntity;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.form.builtin.ItemForm;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.events.EventSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Platform harvest/apply boundary for metadata-driven aggregate saving.
 *
 * <p>The adapter is the only platform class that knows how to translate an
 * {@link ItemForm} into aggregate section inputs and how to put persisted rows
 * back into the form. It does not know application entities or repositories;
 * absent sections are omitted from the request, while an attached empty table
 * remains an explicit clear command.</p>
 */
public final class MetadataDrivenItemFormSaveAdapter {

    private final SectionMetadataRegistry sectionRegistry;
    private final MetadataDrivenAggregateSaveService aggregateSaveService;

    public MetadataDrivenItemFormSaveAdapter(
            SectionMetadataRegistry sectionRegistry,
            MetadataDrivenAggregateSaveService aggregateSaveService) {
        this.sectionRegistry = Objects.requireNonNull(sectionRegistry,
            "sectionRegistry must not be null");
        this.aggregateSaveService = Objects.requireNonNull(aggregateSaveService,
            "aggregateSaveService must not be null");
    }

    /** Сохранить форму через metadata-driven aggregate service и применить persisted state. */
    public <T extends IdentifiableEntity<Long>> FormSaveResult<T> save(ItemForm<T> form) {
        Objects.requireNonNull(form, "form must not be null");
        T aggregate = form.getEntity();
        MetadataDrivenAggregateSaveService.AggregateSaveResult<T> result =
            aggregateSaveService.save(aggregate, harvest(form), EventSource.UI);
        apply(form, result);
        return new FormSaveResult.Success<>(result.aggregate());
    }

    /** Снять только фактически подключённые секции формы. */
    public <T extends IdentifiableEntity<Long>> List<MetadataDrivenAggregateSaveService.SectionInput> harvest(
            ItemForm<T> form) {
        Objects.requireNonNull(form, "form must not be null");
        Class<T> aggregateClass = form.getEntityClass();
        List<TableSectionMetadataInfo> descriptors = sectionRegistry.forOwner(aggregateClass);
        Set<Class<?>> declaredRows = new HashSet<>();
        List<MetadataDrivenAggregateSaveService.SectionInput> result = new ArrayList<>();
        for (TableSectionMetadataInfo descriptor : descriptors) {
            declaredRows.add(descriptor.getRowClass());
            Class<? extends IdentifiableEntity<Long>> rowType = longRowType(descriptor.getRowClass());
            SectionPayload<?> payload = form.sectionPayload(rowType);
            if (payload.isAttached()) {
                result.add(MetadataDrivenAggregateSaveService.SectionInput.attached(
                    rowType, payload.rows()));
            }
        }
        for (Class<?> attachedRow : form.attachedSectionClasses()) {
            if (!declaredRows.contains(attachedRow)) {
                throw new IllegalArgumentException("Form attached undeclared section "
                    + attachedRow.getName() + " for " + aggregateClass.getName());
            }
        }
        return List.copyOf(result);
    }

    /** Применить persisted aggregate и только возвращённые attached sections к форме. */
    public <T extends IdentifiableEntity<Long>> void apply(
            ItemForm<T> form,
            MetadataDrivenAggregateSaveService.AggregateSaveResult<T> result) {
        Objects.requireNonNull(form, "form must not be null");
        Objects.requireNonNull(result, "result must not be null");
        form.applySavedState(() -> {
            form.applyPersistedEntity(result.aggregate());
            for (MetadataDrivenAggregateSaveService.SectionResult section : result.sections()) {
                applySection(form, result.aggregate(), section.rowType(), section.rows());
            }
            form.commitSnapshot();
        });
    }

    private static <T extends IdentifiableEntity<Long>, R extends IdentifiableEntity<Long>> void applySection(
            ItemForm<T> form, T aggregate, Class<R> rowType,
            List<? extends IdentifiableEntity<Long>> rows) {
        form.tableSection(rowType).applyPersistedRows(aggregate, rows.stream().map(rowType::cast).toList());
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends IdentifiableEntity<Long>> longRowType(Class<?> rowType) {
        if (!IdentifiableEntity.class.isAssignableFrom(rowType)
                || org.springframework.core.ResolvableType.forClass(rowType).as(IdentifiableEntity.class)
                    .resolveGeneric(0) != Long.class) {
            throw new IllegalArgumentException("Form section requires IdentifiableEntity<Long>: " + rowType.getName());
        }
        // The interface and its declared identity argument were checked above.
        return (Class<? extends IdentifiableEntity<Long>>) rowType;
    }
}
