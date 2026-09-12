package org.ipro.form;

import org.ipro.crud.IdentifiableEntity;
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
    public <T extends IdentifiableEntity> FormSaveResult<T> save(ItemForm<T> form) {
        Objects.requireNonNull(form, "form must not be null");
        T aggregate = form.getEntity();
        MetadataDrivenAggregateSaveService.AggregateSaveResult<T> result =
            aggregateSaveService.save(aggregate, harvest(form), EventSource.UI);
        apply(form, result);
        return new FormSaveResult.Success<>(result.aggregate());
    }

    /** Снять только фактически подключённые секции формы. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity> List<MetadataDrivenAggregateSaveService.SectionInput> harvest(
            ItemForm<T> form) {
        Objects.requireNonNull(form, "form must not be null");
        Class<T> aggregateClass = form.getEntityClass();
        List<TableSectionMetadataInfo> descriptors = sectionRegistry.forOwner(aggregateClass);
        Set<Class<?>> declaredRows = new HashSet<>();
        List<MetadataDrivenAggregateSaveService.SectionInput> result = new ArrayList<>();
        for (TableSectionMetadataInfo descriptor : descriptors) {
            declaredRows.add(descriptor.getRowClass());
            SectionPayload payload = form.sectionPayload((Class) descriptor.getRowClass());
            if (payload.isAttached()) {
                result.add(MetadataDrivenAggregateSaveService.SectionInput.attached(
                    (Class<? extends IdentifiableEntity>) descriptor.getRowClass(), payload.rows()));
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
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends IdentifiableEntity> void apply(
            ItemForm<T> form,
            MetadataDrivenAggregateSaveService.AggregateSaveResult<T> result) {
        Objects.requireNonNull(form, "form must not be null");
        Objects.requireNonNull(result, "result must not be null");
        form.applyPersistedEntity(result.aggregate());
        for (MetadataDrivenAggregateSaveService.SectionResult section : result.sections()) {
            form.tableSection((Class) section.rowType())
                .applyPersistedRows(result.aggregate(), (List) section.rows());
        }
        form.commitSnapshot();
    }
}
