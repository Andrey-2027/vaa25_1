package org.ipro.form.registry;

import org.ipro.form.FieldFactory;
import org.ipro.form.TableSectionFactory;
import org.ipro.form.builder.ItemFormCustomizer;
import org.ipro.form.builder.ListFormCustomizer;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ListForm;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.BaseService;
import org.ipro.crud.LookupService;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.RowMetadataInfo;
import com.vaadin.flow.component.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Тесты применения кастомайзеров в резолвере (Этап 2): default + вариантные,
 * порядок, работа и на generic, и на кастомных фабриках, strict-варианты целы.
 */
class FormResolverCustomizerTest {

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
        registry = new FormRegistry();
        metadataResolver = mock(MetadataResolver.class);
        fieldFactory = mock(FieldFactory.class);
        applicationContext = mock(ApplicationContext.class);
        tableSectionFactory = mock(TableSectionFactory.class);
        selectionFormAssembler = mock(SelectionFormAssembler.class);
        serviceLocator = mock(ServiceLocator.class);

        when(applicationContext.getBean(LookupService.class))
            .thenReturn(mock(LookupService.class));
        when(serviceLocator.findService(Doc.class))
            .thenReturn(mock(BaseService.class));

        resolver = new FormResolver(registry, metadataResolver, fieldFactory,
            applicationContext, tableSectionFactory, selectionFormAssembler, serviceLocator);
    }

    @Test
    void listGenericAppliesDefaultCustomizer() {
        EntityMetadataInfo meta = entityMetadataFor();
        when(metadataResolver.resolve(Doc.class)).thenReturn(meta);
        registry.addListCustomizer(Doc.class, null, (form, ctx) -> form.setReadOnly(true));

        ListForm<Doc, ?> result = resolver.resolveListForm(Doc.class, null, null);

        assertThat(result.getAddButton().isVisible()).isFalse();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void listCustomFactoryFormAlsoCustomized() {
        EntityMetadataInfo meta = entityMetadataFor();
        ListForm custom = new ListForm<>(meta,
            (BaseService<Doc, ?>) serviceLocator.findService(Doc.class));
        registry.registerListForm(Doc.class, (String) null, ctx -> custom);
        registry.addListCustomizer(Doc.class, null,
            (form, ctx) -> form.getToolbar().add(new com.vaadin.flow.component.html.Span("x")));

        ListForm<Doc, ?> result = resolver.resolveListForm(Doc.class, null, null);

        assertThat(result).isSameAs(custom);
        List<Component> toolbarChildren = custom.getToolbar().getChildren().toList();
        // стандартный состав тулбара: 6 кнопок + spacer + «Ещё» (MenuBar) — состав
        // намеренный и может расти; суть теста — что кастомайзер применился к форме
        // кастомной фабрики и его span добавлен последним, а не точное число детей.
        assertThat(toolbarChildren)
            .filteredOn(child -> child instanceof com.vaadin.flow.component.html.Span)
            .hasSize(1);
        assertThat(toolbarChildren.get(toolbarChildren.size() - 1))
            .isInstanceOf(com.vaadin.flow.component.html.Span.class);
    }

    @Test
    void listDefaultAndVariantCustomizersRunInOrder() {
        EntityMetadataInfo meta = entityMetadataFor();
        when(metadataResolver.resolve(Doc.class)).thenReturn(meta);
        List<String> order = new ArrayList<>();
        registry.addListCustomizer(Doc.class, null, (form, ctx) -> order.add("default"));
        registry.registerListForm(Doc.class, "v",
            ctx -> new ListForm<>(meta,
                (BaseService<Doc, ?>) serviceLocator.findService(Doc.class)));
        registry.addListCustomizer(Doc.class, "v", (form, ctx) -> order.add("v"));

        resolver.resolveListForm(Doc.class, "v", null);

        assertThat(order).containsExactly("default", "v");
    }

    @Test
    void listUnknownVariantStillThrows() {
        registry.addListCustomizer(Doc.class, null, (form, ctx) -> {
        });

        assertThatThrownBy(() -> resolver.resolveListForm(Doc.class, "unknown", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LIST variant 'unknown'");
    }

    @Test
    void itemGenericAppliesCustomizerBeforeTableSections() {
        RowMetadataInfo rowMeta = rowMetadataFor();
        when(metadataResolver.resolveRowMetadata(Doc.class)).thenReturn(rowMeta);
        ItemFormCustomizer customizer = mock(ItemFormCustomizer.class);
        registry.addItemCustomizer(Doc.class, null, customizer);

        ItemForm<Doc> result = resolver.resolveItemForm(Doc.class, null, null, null);

        InOrder inOrder = inOrder(customizer, tableSectionFactory);
        inOrder.verify(customizer).customize(eq(result), any(FormContext.class));
        inOrder.verify(tableSectionFactory).attachTableSections(result, Doc.class);
    }

    /**
     * D3.5.1: контекст больше не несёт ApplicationContext и concrete LookupService
     * (см. NomAttributeValueFormConfig — зависимости приходят конструктором Spring-бина).
     * Резолвер кладёт типизированные metadataResolver/fieldFactory/entityLookup;
     * навигацию подставляет только координатор, поэтому здесь formNavigator — null.
     */
    @Test
    void itemCustomFactoryReceivesTypedContextWithoutServiceLocator() {
        List<FormContext> contexts = new ArrayList<>();
        registry.registerItemForm(Doc.class, (String) null, ctx -> {
            contexts.add(ctx);
            return new ItemForm<>(Doc.class, List.of(), fieldFactory);
        });

        resolver.resolveItemForm(Doc.class, null, 42L, null);

        assertThat(contexts).hasSize(1);
        FormContext ctx = contexts.get(0);
        assertThat(ctx.metadataResolver()).isSameAs(metadataResolver);
        assertThat(ctx.fieldFactory()).isSameAs(fieldFactory);
        assertThat(ctx.entityLookup()).isSameAs(applicationContext.getBean(LookupService.class));
        assertThat(ctx.formNavigator()).isNull();
        assertThat(ctx.getId()).isEqualTo(42L);
    }

    @Test
    void itemGenericAppliesBehavioralChange() {
        RowMetadataInfo rowMeta = rowMetadataFor();
        when(metadataResolver.resolveRowMetadata(Doc.class)).thenReturn(rowMeta);
        registry.addItemCustomizer(Doc.class, null, (form, ctx) -> form.setReadOnly(true));

        ItemForm<Doc> result = resolver.resolveItemForm(Doc.class, null, null, null);

        verify(tableSectionFactory).attachTableSections(result, Doc.class);
    }

    private static EntityMetadataInfo entityMetadataFor() {
        EntityMetadataInfo meta = mock(EntityMetadataInfo.class);
        doReturn(Doc.class).when(meta).getEntityClass();
        when(meta.getListColumnPaths()).thenReturn(List.of());
        return meta;
    }

    private static RowMetadataInfo rowMetadataFor() {
        RowMetadataInfo meta = mock(RowMetadataInfo.class);
        when(meta.getFormFields()).thenReturn(List.of());
        return meta;
    }

    static class Doc extends BaseEntity {
    }
}
