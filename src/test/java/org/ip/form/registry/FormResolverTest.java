package org.ip.form.registry;

import com.vaadin.flow.component.html.Div;
import org.ip.form.FieldFactory;
import org.ip.form.SelectionFormAssembler;
import org.ip.form.TableSectionFactory;
import org.ip.form.builtin.ItemForm;
import org.ip.form.builtin.ListForm;
import org.ip.form.builtin.SelectionForm;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.MetadataResolver;
import org.ip.metadata.RowMetadataInfo;
import org.ip.service.BaseService;
import org.ip.service.LookupService;
import org.ip.service.ServiceLocator;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.IdentifiableEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Тесты стратегии разрешения форм (Form Resolution Strategy, PR-1.4 «strict variants»):
 * named variant → кастомная форма, default (variant = null) → default или generic,
 * неизвестный non-null variant → IllegalStateException (fallback запрещён),
 * некорректный пользовательский тип формы — ошибка.
 */
class FormResolverTest {

    private FormRegistry registry;
    private MetadataResolver metadataResolver;
    private FieldFactory fieldFactory;
    private ApplicationContext applicationContext;
    private TableSectionFactory tableSectionFactory;
    private SelectionFormAssembler selectionFormAssembler;
    private ServiceLocator serviceLocator;

    private FormResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        registry = mock(FormRegistry.class);
        metadataResolver = mock(MetadataResolver.class);
        fieldFactory = mock(FieldFactory.class);
        applicationContext = mock(ApplicationContext.class);
        tableSectionFactory = mock(TableSectionFactory.class);
        selectionFormAssembler = mock(SelectionFormAssembler.class);
        serviceLocator = mock(ServiceLocator.class);

        when(applicationContext.getBean(LookupService.class))
            .thenReturn(mock(LookupService.class));
        when(serviceLocator.findService(PlainEntity.class))
            .thenReturn(mock(BaseService.class));

        resolver = new FormResolver(registry, metadataResolver, fieldFactory,
            applicationContext, tableSectionFactory, selectionFormAssembler, serviceLocator);
    }

    // === ITEM ===

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void itemNamedVariantUsesCustomFactoryAndAttachesSections() {
        EntityMetadataInfo meta = entityMetadataFor(PlainEntity.class);
        ItemForm custom = new ItemForm<>(meta, fieldFactory);
        when(registry.findItemForm(PlainEntity.class, "brief")).thenReturn(ctx -> custom);

        ItemForm<PlainEntity> result = resolver.resolveItemForm(PlainEntity.class, "brief", null, null);

        assertThat(result).isSameAs(custom);
        verify(registry).findItemForm(PlainEntity.class, "brief");
        verify(tableSectionFactory).attachTableSections(result, PlainEntity.class);
    }

    @Test
    void itemUnknownNamedVariantThrowsInsteadOfFallback() {
        when(registry.findItemForm(PlainEntity.class, "unknown")).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveItemForm(PlainEntity.class, "unknown", null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ITEM variant 'unknown'")
            .hasMessageContaining(PlainEntity.class.getName());
        verify(registry, never()).findItemForm(PlainEntity.class, null);
    }

    @Test
    void itemWithoutRegistrationFallsBackToGeneric() {
        when(registry.findItemForm(PlainEntity.class, null)).thenReturn(null);
        RowMetadataInfo rowMeta = rowMetadataFor();
        when(metadataResolver.resolveRowMetadata(PlainEntity.class)).thenReturn(rowMeta);

        ItemForm<PlainEntity> result = resolver.resolveItemForm(PlainEntity.class, null, null, null);

        assertThat(result).isNotNull();
        assertThat(result.getTableSections()).isNotNull();
        verify(tableSectionFactory).attachTableSections(result, PlainEntity.class);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void itemFactoryReturningWrongComponentThrows() {
        when(registry.findItemForm(PlainEntity.class, "bad")).thenReturn(ctx -> new Div());

        assertThatThrownBy(() -> resolver.resolveItemForm(PlainEntity.class, "bad", null, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("instead of ItemForm");
        verify(tableSectionFactory, never()).attachTableSections(any(), any());
    }

    // === LIST ===

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listNamedVariantUsesCustomFactory() {
        EntityMetadataInfo meta = entityMetadataFor(PlainEntity.class);
        ListForm custom = new ListForm<>(meta, (BaseService<PlainEntity, ?>) serviceLocator.findService(PlainEntity.class));
        when(registry.findListForm(PlainEntity.class, "archived")).thenReturn(ctx -> custom);

        ListForm<PlainEntity, ?> result = resolver.resolveListForm(PlainEntity.class, "archived", null);

        assertThat(result).isSameAs(custom);
        verify(registry).findListForm(PlainEntity.class, "archived");
    }

    @Test
    void listUnknownNamedVariantThrowsInsteadOfFallback() {
        when(registry.findListForm(PlainEntity.class, "unknown")).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveListForm(PlainEntity.class, "unknown", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LIST variant 'unknown'")
            .hasMessageContaining(PlainEntity.class.getName());
        verify(registry, never()).findListForm(PlainEntity.class, null);
    }

    @Test
    void listWithoutRegistrationFallsBackToGeneric() {
        when(registry.findListForm(PlainEntity.class, null)).thenReturn(null);
        EntityMetadataInfo meta = entityMetadataFor(PlainEntity.class);
        when(metadataResolver.resolve(PlainEntity.class)).thenReturn(meta);

        ListForm<PlainEntity, ?> result = resolver.resolveListForm(PlainEntity.class, null, null);

        assertThat(result).isNotNull();
        assertThat(result).isInstanceOf(ListForm.class);
    }

    @Test
    void listFactoryReturningWrongComponentThrows() {
        when(registry.findListForm(PlainEntity.class, "bad")).thenReturn(ctx -> new Div());

        assertThatThrownBy(() -> resolver.resolveListForm(PlainEntity.class, "bad", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("instead of ListForm");
    }

    // === SELECTION ===

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void selectionFormDelegatesToSelectionFormAssembler() {
        SelectionForm selection = mock(SelectionForm.class);
        Consumer<PlainEntity> onSelect = e -> {
        };
        when(selectionFormAssembler.<PlainEntity, Long>assemble(PlainEntity.class, onSelect))
            .thenReturn(selection);

        SelectionForm<PlainEntity> result = resolver.resolveSelectionForm(PlainEntity.class, onSelect);

        assertThat(result).isSameAs(selection);
    }

    // === helpers ===

    private static EntityMetadataInfo entityMetadataFor(Class<?> entityClass) {
        EntityMetadataInfo meta = mock(EntityMetadataInfo.class);
        doReturn(entityClass).when(meta).getEntityClass();
        when(meta.getListColumnPaths()).thenReturn(List.of());
        return meta;
    }

    private static RowMetadataInfo rowMetadataFor() {
        RowMetadataInfo meta = mock(RowMetadataInfo.class);
        when(meta.getFormFields()).thenReturn(List.of());
        return meta;
    }

    /** Обычная сущность без {@code @EntityMetadata} — идёт по generic-пути. */
    static class PlainEntity extends BaseEntity {
    }
}
