package org.ipro.form.registry;

import org.ipro.crud.BaseService;
import org.ipro.crud.LookupService;
import org.ipro.crud.ServiceLocator;
import org.ipro.crud.StandardCatalogEntity;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.TableSectionFactory;
import org.ipro.form.builder.ListFormVariants;
import org.ipro.form.builder.SelectionFormVariants;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.FormColumnDeclarations;
import org.ipro.metadata.MetadataResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FormResolverListColumnsTest {
    @jakarta.persistence.Entity
    static class Catalog extends StandardCatalogEntity { }
    @jakarta.persistence.Entity
    static class OtherCatalog extends StandardCatalogEntity { }

    @Test
    void automaticNamedListUsesItsColumnsAndResetKeepsTheVariant() {
        var declarations = new FormColumnDeclarations();
        declarations.register(new FormColumnDeclarations.Declaration(Catalog.class,
            FormColumnDeclarations.Kind.LIST, null, List.of("name"), "", "CatalogFormConfig"));
        declarations.register(new FormColumnDeclarations.Declaration(Catalog.class,
            FormColumnDeclarations.Kind.LIST, "compact", List.of("code"), "", "CatalogFormConfig"));
        declarations.freeze();
        var metadata = new MetadataResolver();
        metadata.setFormColumnDeclarations(declarations);
        var registry = new FormRegistry();
        registry.setFormColumnDeclarations(declarations);
        var services = mock(ServiceLocator.class);
        doReturn(mock(BaseService.class)).when(services).findService(Catalog.class);
        var context = mock(ApplicationContext.class);
        when(context.getBean(LookupService.class)).thenReturn(mock(LookupService.class));
        var resolver = new FormResolver(registry, metadata, mock(FieldFactory.class), context,
            mock(TableSectionFactory.class), mock(SelectionFormAssembler.class), services);
        var defaultMetadata = metadata.resolve(Catalog.class);

        var variant = resolver.resolveListForm(Catalog.class, "compact", Map.of());
        assertThat(variant.getActiveColumns()).extracting(ColumnPath::getKey).containsExactly("code");
        variant.resetActiveColumns();
        assertThat(variant.getActiveColumns()).extracting(ColumnPath::getKey).containsExactly("code");
        assertThat(metadata.resolve(Catalog.class)).isSameAs(defaultMetadata);
        assertThat(defaultMetadata.getListColumnPaths()).extracting(ColumnPath::getKey).containsExactly("name");
        var defaultForm = resolver.resolveListForm(Catalog.class, null, Map.of());
        assertThat(defaultForm.getActiveColumns()).extracting(ColumnPath::getKey).containsExactly("name");
        assertThatThrownBy(() -> resolver.resolveListForm(Catalog.class, "unknown", Map.of()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("unknown");
    }

    @Test
    void defaultColumnsMustUseAnnotationsAndDuplicateNamedColumnsAreRejected() {
        var lists = new ListFormVariants();
        var selections = new SelectionFormVariants();
        assertThatThrownBy(() -> lists.addColumns(null, "code")).hasMessageContaining("@ListColumns");
        assertThatThrownBy(() -> selections.addColumns("", "code")).hasMessageContaining("@SelectionColumns");
        lists.addColumns("compact", "code");
        selections.addColumns("compact", "code");
        assertThatThrownBy(() -> lists.addColumns("compact", "name")).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> selections.addColumns("compact", "name")).hasMessageContaining("Duplicate");
    }

    private FormResolver resolver(MetadataResolver metadata, FormRegistry registry) {
        var services = mock(ServiceLocator.class);
        doReturn(mock(BaseService.class)).when(services).findService(Catalog.class);
        doReturn(mock(BaseService.class)).when(services).findService(OtherCatalog.class);
        var context = mock(ApplicationContext.class);
        when(context.getBean(LookupService.class)).thenReturn(mock(LookupService.class));
        return new FormResolver(registry, metadata, mock(FieldFactory.class), context,
            mock(TableSectionFactory.class), mock(SelectionFormAssembler.class), services);
    }

    @Test
    void savedWidthOverridesFormAndResetRestoresDeclaration() {
        var columns = FormColumnDeclarations.auto();
        columns.column("code").setWidth("8em").setFlexGrow(0);
        var declarations = new FormColumnDeclarations();
        declarations.register(new FormColumnDeclarations.Declaration(Catalog.class,
            FormColumnDeclarations.Kind.LIST, null, columns, "", "CatalogConfig"));
        declarations.freeze();
        var metadata = new MetadataResolver();
        metadata.setFormColumnDeclarations(declarations);
        var registry = new FormRegistry();
        registry.setFormColumnDeclarations(declarations);
        var form = resolver(metadata, registry).resolveListForm(Catalog.class, null, Map.of());
        assertThat(form.getGrid().getColumnByKey("code").getWidth()).isEqualTo("8em");
        assertThat(form.getGrid().getColumnByKey("code").getFlexGrow()).isZero();
        form.getGrid().getColumnByKey("code").setWidth("23em");
        var saved = form.captureViewState();
        assertThat(saved.columns().stream().filter(spec -> spec.path().equals("code")).findFirst().orElseThrow().width())
            .isEqualTo("23em");
        form.resetActiveColumns();
        form.applyViewState(saved);
        assertThat(form.getGrid().getColumnByKey("code").getWidth()).isEqualTo("23em");
        form.resetActiveColumns();
        assertThat(form.getGrid().getColumnByKey("code").getWidth()).isEqualTo("8em");
        assertThat(metadata.resolve(Catalog.class).getListColumnPaths().stream()
            .filter(path -> path.getKey().equals("code")).findFirst().orElseThrow().getWidth()).isEqualTo("8em");
    }

    @Test
    void familyDisablesOnlyHeaderFilterAcrossItsListsIncludingAfterResetAndSavedView() {
        var metadata = new MetadataResolver();
        var resolver = resolver(metadata, new FormRegistry());
        resolver.setTypeCustomizations(List.of(new org.ipro.form.builder.FormTypeCustomization() {
            public boolean supports(Class<?> type) { return StandardCatalogEntity.class.isAssignableFrom(type); }
            public void configureListColumns(Class<?> type, FormColumnDeclarations.Columns columns) {
                columns.column("code").setInlineFilter(false);
            }
        }));
        for (var type : List.of(Catalog.class, OtherCatalog.class)) {
            var form = resolver.resolveListForm(type, null, Map.of());
            assertThat(form.getGrid().getColumnByKey("code")).isNotNull();
            assertThat((Object) form.getFilterGrid().getColumnFilter("code")).isNull();
            assertThat((Object) form.getFilterGrid().getColumnFilter("name")).isNotNull();
            assertThat(form.compileFilter(org.ipro.filtergrid.filter.FilterConditionNode.of(
                new org.ipro.filtergrid.filter.FilterCondition("code", org.ipro.filtergrid.filter.FilterOperator.EQ,
                    "A", null, org.ipro.filtergrid.filter.FilterDataType.TEXT)))).isNotNull();
            form.applyViewState(new org.ipro.metadata.GridViewState(List.of(new ColumnPath.Spec("code", null)), List.of()));
            assertThat((Object) form.getFilterGrid().getColumnFilter("code")).isNull();
            form.resetActiveColumns();
            assertThat((Object) form.getFilterGrid().getColumnFilter("code")).isNull();
            assertThat(metadata.resolve(type).getListColumnPaths().stream().filter(path -> path.getKey().equals("code"))
                .findFirst().orElseThrow().isInlineFilterEnabled()).isTrue();
        }
    }

    @Test
    void invalidFamilyPropertyFailsStartupValidationWithoutConstructingForms() {
        var metadata = new MetadataResolver();
        var registry = new FormRegistry();
        var resolver = resolver(metadata, registry);
        resolver.setTypeCustomizations(List.of(new org.ipro.form.builder.FormTypeCustomization() {
            public boolean supports(Class<?> type) { return type == Catalog.class; }
            public void configureListColumns(Class<?> type, FormColumnDeclarations.Columns columns) {
                columns.column("missing").setInlineFilter(false);
            }
        }));
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("types", new org.ipro.metadata.ManagedEntityTypes(List.of(Catalog.class)));
        var validation = new org.ipro.form.config.FormAutoConfiguration().formColumnPropertiesValidation(
            resolver, metadata, beans.getBeanProvider(org.ipro.metadata.ManagedEntityTypes.class), registry);
        assertThatThrownBy(validation::afterSingletonsInstantiated).hasMessageContaining("missing")
            .hasMessageContaining(Catalog.class.getName()).hasMessageContaining("outside composition");
    }
}
