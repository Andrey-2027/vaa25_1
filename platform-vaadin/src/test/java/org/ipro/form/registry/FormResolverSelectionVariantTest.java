package org.ipro.form.registry;

import org.ipro.form.FieldFactory;
import org.ipro.form.TableSectionFactory;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.BaseService;
import org.ipro.crud.LookupService;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.SelectionForm;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.GridColumn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Тесты SELECTION-ветки {@link FormResolver} (Этап 1, strict variants):
 * именованный вариант → кастомная фабрика либо data-набор колонок,
 * неизвестный вариант → IllegalStateException, default → фабрика либо generic.
 */
class FormResolverSelectionVariantTest {

    private FormRegistry registry;
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
        fieldFactory = mock(FieldFactory.class);
        applicationContext = mock(ApplicationContext.class);
        tableSectionFactory = mock(TableSectionFactory.class);
        selectionFormAssembler = mock(SelectionFormAssembler.class);
        serviceLocator = mock(ServiceLocator.class);

        when(applicationContext.getBean(LookupService.class))
            .thenReturn(mock(LookupService.class));
        when(serviceLocator.findService(Unit.class))
            .thenReturn(mock(BaseService.class));

        resolver = new FormResolver(registry, new MetadataResolver(), fieldFactory,
            applicationContext, tableSectionFactory, selectionFormAssembler, serviceLocator);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void selectionNamedVariantUsesCustomFactory() {
        SelectionForm selection = mock(SelectionForm.class);
        Consumer<Unit> onSelect = e -> {
        };
        when(registry.findSelectionForm(Unit.class, "brief")).thenReturn(ctx -> selection);

        SelectionForm<Unit> result = resolver.resolveSelectionForm(Unit.class, "brief", onSelect, Map.of());

        assertThat(result).isSameAs(selection);
        verify(registry).findSelectionForm(Unit.class, "brief");
    }

    @Test
    void selectionUnknownVariantThrowsInsteadOfFallback() {
        Consumer<Unit> onSelect = e -> {
        };
        when(registry.findSelectionForm(Unit.class, "unknown")).thenReturn(null);
        when(registry.getSelectionColumns(Unit.class, "unknown")).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveSelectionForm(Unit.class, "unknown", onSelect, Map.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SELECTION variant 'unknown'")
            .hasMessageContaining(Unit.class.getName());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void selectionDefaultFactoryUsedWhenRegistered() {
        SelectionForm selection = mock(SelectionForm.class);
        Consumer<Unit> onSelect = e -> {
        };
        when(registry.findSelectionForm(Unit.class, null)).thenReturn(ctx -> selection);

        SelectionForm<Unit> result = resolver.resolveSelectionForm(Unit.class, onSelect);

        assertThat(result).isSameAs(selection);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void selectionGenericFallbackDelegatesToAssembler() {
        SelectionForm selection = mock(SelectionForm.class);
        Consumer<Unit> onSelect = e -> {
        };
        when(registry.findSelectionForm(Unit.class, null)).thenReturn(null);
        when(selectionFormAssembler.<Unit, Long>assemble(eq(Unit.class), eq(onSelect), anyMap()))
            .thenReturn(selection);

        SelectionForm<Unit> result = resolver.resolveSelectionForm(Unit.class, onSelect);

        assertThat(result).isSameAs(selection);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void selectionVariantColumnsAssembledWithVariant() {
        SelectionForm selection = mock(SelectionForm.class);
        Consumer<Unit> onSelect = e -> {
        };
        when(registry.findSelectionForm(Unit.class, "compact")).thenReturn(null);
        when(registry.getSelectionColumns(Unit.class, "compact"))
            .thenReturn(SelectionColumnsDef.of(List.of("code"), "Кратко"));
        org.mockito.ArgumentCaptor<SelectionFormAssembler.ResolvedSelection> columns =
            org.mockito.ArgumentCaptor.forClass(SelectionFormAssembler.ResolvedSelection.class);
        when(selectionFormAssembler.<Unit, Long>assemble(
            eq(Unit.class), eq(onSelect), anyMap(), columns.capture(), eq("compact")))
            .thenReturn(selection);

        SelectionForm<Unit> result =
            resolver.resolveSelectionForm(Unit.class, "compact", onSelect, Map.of());

        assertThat(result).isSameAs(selection);
        assertThat(columns.getValue().columns()).extracting(p -> p.getKey()).containsExactly("code");
        assertThat(columns.getValue().title()).isEqualTo("Кратко");
        verify(selectionFormAssembler).assemble(any(), any(), anyMap(), any(), eq("compact"));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void selectionAttachesDeclaredContextFilters() {
        SelectionForm selection = mock(SelectionForm.class);
        Consumer<Unit> onSelect = e -> {
        };
        when(registry.findSelectionForm(Unit.class, null)).thenReturn(null);
        when(registry.getContextFilters(Unit.class)).thenReturn(List.of(
            org.ipro.form.builder.ContextFilterField.auto("code", "Код").allListVariants()));
        when(selectionFormAssembler.<Unit, Long>assemble(eq(Unit.class), eq(onSelect), anyMap()))
            .thenReturn(selection);

        SelectionForm<Unit> result = resolver.resolveSelectionForm(
            Unit.class, onSelect, Map.of("kind", "A"));

        assertThat(result).isSameAs(selection);
        verify(selection).setFixedFilters(Map.of("kind", "A"));
        verify(selection).setHeaderRow(any(com.vaadin.flow.component.Component.class));
    }

    @EntityMetadata(listFormTitle = "ЕИ", selectionFormTitle = "Выбор ЕИ",
        selectColumns = {"code", "name"})
    static class Unit extends BaseEntity {
        @FieldMetadata(label = "Код", grid = @GridColumn(order = 1))
        String code;
        @FieldMetadata(label = "Наименование", grid = @GridColumn(order = 2))
        String name;
    }
}
