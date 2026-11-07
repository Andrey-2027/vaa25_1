package org.ip.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.Query;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.*;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.EntityField;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.layout.ItemFormLayout;
import org.ipro.form.builder.layout.Layout;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormResolver;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Real forms and canonical readers: dependencies are usable on detached objects in both channels. */
@SpringBootTest(classes = Application.class)
@Transactional
class FormReadDependenciesIT {
    @MockitoBean DataInitializer initializer;
    @Autowired FormResolver forms;
    @Autowired FormRegistry registry;
    @Autowired FieldFactory fields;
    @Autowired SelectionFormAssembler selections;
    @Autowired org.ipro.crud.LookupService lookups;
    @Autowired org.ipro.form.TableSectionFactory tables;
    @Autowired ServiceLocator services;
    @Autowired MetadataResolver metadata;
    @Autowired org.ipro.fetch.plan.FetchPlanRegistry fetchPlans;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired AccessGrantRepository grants;
    @Autowired org.ip.repository.JournalRepository journals;
    @Autowired org.ipro.crud.MetadataDrivenAggregateSaveService aggregates;
    private PrdSpec spec;
    private String prefix, shortCode;

    @BeforeEach void seed() {
        String user = RlsTestFixture.authenticateAsSuperuser(grants);
        RlsTestFixture.grantC5EntityRead(grants, user, PrdSpec.class, PrdSpecMtr.class,
            Nomenclature.class, UnitOfMeasurement.class, Journal.class);
        prefix = "af56" + UUID.randomUUID().toString().substring(0, 6);
        shortCode = "u" + prefix.substring(4);
        var unit = new UnitOfMeasurement(shortCode, "Unit " + prefix, "c" + prefix.substring(4));
        em.persist(unit);
        var nom = new Nomenclature(prefix, "Material " + prefix, unit);
        nom.setTypeNom("Материал");
        em.persist(nom);
        var excluded = new Nomenclature(prefix + "x", "Assembly " + prefix, unit);
        excluded.setTypeNom("Узел");
        em.persist(excluded);
        var journal = new Journal();
        journal.setCode(prefix);
        journal.setName("Journal " + prefix);
        journals.save(journal);
        var component = new PrdSpec();
        component.setCodeSpec(prefix + "c");
        component.setJournal(journal);
        component.setNomenclature(nom);
        component = aggregates.save(component, List.of(), org.ipro.events.EventSource.SYSTEM).aggregate();
        spec = new PrdSpec();
        spec.setCodeSpec(prefix);
        spec.setJournal(journal);
        spec.setNomenclature(excluded);
        var rows = new java.util.ArrayList<PrdSpecMtr>();
        for (int type : List.of(0, 1)) {
            var row = new PrdSpecMtr();
            row.setPrdSpec(spec);
            row.setTypeMtr(type);
            row.setNomenclature(nom);
            if (type == 1) row.setPrdSpecMtr(component);
            row.setUnit(unit);
            row.setQt(BigDecimal.ONE);
            row.setLineNumber(type + 1);
            rows.add(row);
        }
        spec = aggregates.save(spec, List.of(new org.ipro.crud.MetadataDrivenAggregateSaveService.SectionInput(
            PrdSpecMtr.class, rows)), org.ipro.events.EventSource.SYSTEM).aggregate();
        em.flush();
        em.clear();
    }

    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    private static List<Component> descendants(Component component) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(component),
            component.getChildren().flatMap(child -> descendants(child).stream())).toList();
    }

    @Test
    @SuppressWarnings("unchecked")
    void materialAutocompleteRetainsFilterAndSelectionHandlerWorksAfterDetachment() {
        ItemForm<PrdSpecMtr> form = forms.resolveItemForm(PrdSpecMtr.class, "material", null, null);
        EntityField<Nomenclature> field = form.entityField("nomenclature");
        var text = descendants(field).stream().filter(TextField.class::isInstance)
            .map(TextField.class::cast).findFirst().orElseThrow();
        text.setValue(prefix);
        Grid<Nomenclature> suggestions = (Grid<Nomenclature>) descendants(field).stream()
            .filter(Grid.class::isInstance).findFirst().orElseThrow();
        var found = suggestions.getListDataView().getItems().toList();
        assertThat(found).extracting(Nomenclature::getCode).containsExactly(prefix);
        em.clear();
        assertThat(Hibernate.isInitialized(found.getFirst().getUnitOfMeasurement())).isTrue();
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        long before = statistics.getPrepareStatementCount();
        suggestions.asSingleSelect().setValue(found.getFirst());
        assertThat(form.<UnitOfMeasurement>entityField("unit").getValue().getShortCode()).isEqualTo(shortCode);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(before);
    }

    @Test void selectionDialogsHydrateTheMaterialAndProductDependencies() {
        var material = forms.resolveItemForm(PrdSpecMtr.class, "material", null, null);
        var materialSelection = selections.assemble(Nomenclature.class, value -> { },
            Map.of("typeNom", "Материал"), selections.resolveColumns(Nomenclature.class), null,
            material.entityField("nomenclature").getLookupPaths());
        var selected = materialSelection.getFilterGrid().getGrid().getDataProvider().fetch(new Query<>())
            .filter(value -> value.getCode().startsWith(prefix)).toList();
        assertThat(selected).extracting(Nomenclature::getCode).containsExactly(prefix);
        em.clear();
        assertThat(selected.getFirst().getUnitOfMeasurement().getShortCode()).isEqualTo(shortCode);

        var product = forms.resolveItemForm(PrdSpecMtr.class, "product", null, null);
        var productSelection = selections.assemble(PrdSpec.class, value -> { },
            Map.of("codeSpec", prefix), selections.resolveColumns(PrdSpec.class), null,
            product.entityField("prdSpecMtr").getLookupPaths());
        var selectedSpecs = productSelection.getFilterGrid().getGrid().getDataProvider().fetch(new Query<>()).toList();
        assertThat(selectedSpecs).hasSize(1);
        em.clear();
        assertThat(selectedSpecs.getFirst().getNomenclature().getUnitOfMeasurement().getShortCode())
            .isEqualTo(shortCode);
        chooseInDialog(product.entityField("prdSpecMtr"), selectedSpecs.getFirst());
        assertThat(product.<UnitOfMeasurement>entityField("unit").getValue().getShortCode()).isEqualTo(shortCode);
    }

    private static void chooseInDialog(EntityField<?> field, org.ipro.metadata.HasDisplayName value) {
        try {
            var selected = EntityField.class.getDeclaredMethod("onSelectedInDialog", org.ipro.metadata.HasDisplayName.class);
            selected.setAccessible(true);
            selected.invoke(field, value);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Selection callback failed", failure);
        }
    }

    @Test void rowReadsUseFormDependenciesEvenWhenTheUnitIsAbsentFromVisibleColumns() throws Exception {
        ItemForm<PrdSpec> form = forms.resolveItemForm(PrdSpec.class, null, spec.getId(), null);
        org.ipro.crud.CrudService<PrdSpec> service = services.findService(PrdSpec.class);
        var loaded = service.findById(spec.getId()).orElseThrow();
        form.setEntity(loaded);
        var table = form.<PrdSpecMtr>tableSection(PrdSpecMtr.class);
        List<PrdSpecMtr> rows = table.getRows();
        assertThat(rows).hasSize(2);
        assertDetachedRowUnits(rows);
        var apply = org.ipro.form.builtin.ItemTable.class.getDeclaredMethod("applyView", org.ipro.form.spi.GridView.class);
        apply.setAccessible(true);
        apply.invoke(table, new org.ipro.form.spi.GridView(7L, "PrdSpecMtr", "Quantity only",
            "{\"columns\":[{\"path\":\"qt\"}]}", false, "test"));
        assertThat(table.getActiveColumns()).extracting(org.ipro.metadata.ColumnPath::getKey).containsExactly("qt");
        assertDetachedRowUnits(table.getRows());
        table.resetActiveColumns();
        assertDetachedRowUnits(table.getRows());
        var copied = tables.copyRowsFor(PrdSpec.class, loaded, new PrdSpec(), new org.ipro.crud.EntityCopyService());
        @SuppressWarnings("unchecked") var copiedRows = (List<PrdSpecMtr>) copied.get(PrdSpecMtr.class);
        assertThat(copiedRows).hasSize(2);
        assertDetachedRowUnits(copiedRows);
    }

    private void assertDetachedRowUnits(List<PrdSpecMtr> rows) {
        em.clear();
        for (var row : rows) {
            assertThat(row.getNomenclature().getUnitOfMeasurement().getShortCode()).isEqualTo(shortCode);
            if (row.getPrdSpecMtr() != null)
                assertThat(row.getPrdSpecMtr().getNomenclature().getUnitOfMeasurement().getShortCode())
                    .isEqualTo(shortCode);
        }
    }

    static class NestedDisplayForm extends ItemForm<PrdSpec> {
        @Override protected ItemFormLayout layout(Layout l) {
            return l.of(l.display("nomenclature.unitOfMeasurement.shortCode"),
                l.display("formatted", value -> ((PrdSpec) value).getNomenclature()
                    .getUnitOfMeasurement().getShortCode()).dependsOn("nomenclature.unitOfMeasurement"));
        }
    }

    @Test void nestedDisplayAndFormatterRenderDetachedDetailWithoutExtraQueries() {
        var form = new NestedDisplayForm();
        form.buildDescription(metadata.resolve(PrdSpec.class), fields);
        assertThat(form.getReadPaths()).contains("nomenclature.unitOfMeasurement");
        var loaded = services.<PrdSpec, Long>findService(PrdSpec.class)
            .findById(spec.getId(), form.getReadPaths()).orElseThrow();
        em.clear();
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        long before = statistics.getPrepareStatementCount();
        form.setEntity(loaded);
        assertThat(descendants(form).stream().filter(TextField.class::isInstance)
            .map(TextField.class::cast).map(TextField::getValue).toList()).contains(shortCode);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(before);
    }

    @Test void currentAccountingTypeRemainsReadOnlyInDefaultAndExistingCustomRowForms() {
        for (String variant : java.util.Arrays.asList(null, "accounting-correction-row")) {
            var form = forms.resolveItemForm(AccountingObjectCorrectionItem.class, variant, null, null);
            var binding = form.getBindingRegistry().getBinding("currentAccountingType").orElseThrow();
            assertThat(binding.isReadOnly()).isTrue();
            form.setReadOnly(false);
            assertThat(binding.isReadOnly()).isTrue();
        }
        assertThat(metadata.resolveRowMetadata(AccountingObjectCorrectionItem.class).getFormFields().stream()
            .filter(field -> field.getName().equals("currentAccountingType")).findFirst().orElseThrow().isReadOnly())
            .isFalse();
    }

    @Test void productFillsOnlyOnUserSelectionAndPreservesAuthoritativeSavedValues() {
        var manualUnit = new UnitOfMeasurement("m" + prefix.substring(4), "Manual unit", "M" + prefix.substring(4));
        em.persist(manualUnit);
        var manualNom = new Nomenclature(prefix + "z", "Stored nomenclature", manualUnit);
        em.persist(manualNom); em.flush(); em.clear();
        ItemForm<PrdSpecMtr> form = forms.resolveItemForm(PrdSpecMtr.class, "product", null, null);
        EntityField<PrdSpec> source = form.entityField("prdSpecMtr");
        var specs = lookups.search(PrdSpec.class, new String[]{"codeSpec"}, prefix, 20, source.getLookupPaths());
        var first = specs.stream().filter(value -> value.getCodeSpec().equals(prefix)).findFirst().orElseThrow();
        var second = specs.stream().filter(value -> value.getCodeSpec().equals(prefix + "c")).findFirst().orElseThrow();
        em.clear();
        var stored = new PrdSpecMtr(); stored.setTypeMtr(1); stored.setPrdSpecMtr(first);
        stored.setUnit(manualUnit); stored.setNomenclature(manualNom);
        form.setEntity(stored);
        source.setValue(second);
        assertThat(form.<UnitOfMeasurement>entityField("unit").getValue()).isSameAs(manualUnit);
        assertThat(form.getEntity().getNomenclature()).isSameAs(manualNom);
        chooseInDialog(source, first);
        assertThat(form.<UnitOfMeasurement>entityField("unit").getValue().getShortCode()).isEqualTo(shortCode);
        assertThat(form.getEntity().getNomenclature().getId()).isEqualTo(first.getNomenclature().getId());
        var saved = new PrdSpecMtr(); saved.setTypeMtr(1); saved.setPrdSpecMtr(second);
        saved.setUnit(manualUnit); saved.setNomenclature(manualNom);
        form.applySavedState(() -> form.applyPersistedEntity(saved));
        assertThat(form.<UnitOfMeasurement>entityField("unit").getValue()).isSameAs(manualUnit);
        assertThat(form.getEntity().getNomenclature()).isSameAs(manualNom);
        assertThat(form.isDirty()).isFalse();
    }

    @Test void scopedReadRequirementsMatchTheirOwnSnapshot() throws Exception {
        var out = new StringBuilder("# AF5.6 scoped form read requirements\n");
        for (String variant : java.util.Arrays.asList(null, "material", "product")) {
            var form = forms.resolveItemForm(PrdSpecMtr.class, variant, null, null);
            out.append("\n== PrdSpecMtr ITEM ").append(variant == null ? "default" : variant).append('\n');
            out.append("read: ").append(String.join(",", form.getReadPaths().stream().sorted().toList())).append('\n');
            for (var binding : form.getBindingRegistry().getAll().stream()
                    .sorted(java.util.Comparator.comparing(value -> value.getFieldName())).toList()) {
                if (binding.getComponent() instanceof EntityField<?> reference && !reference.getLookupPaths().isEmpty())
                    out.append("lookup.").append(binding.getFieldName()).append(": ")
                        .append(String.join(",", reference.getLookupPaths().stream().sorted().toList())).append('\n');
            }
        }
        out.append("\nbase.LOOKUP.Nomenclature: ").append(String.join(",",
            fetchPlans.paths(Nomenclature.class, org.ipro.fetch.plan.FetchScenario.LOOKUP))).append('\n');
        out.append("base.LOOKUP.PrdSpec: ").append(String.join(",",
            fetchPlans.paths(PrdSpec.class, org.ipro.fetch.plan.FetchScenario.LOOKUP))).append('\n');
        for (String variant : java.util.Arrays.asList(null, "accounting-correction-row")) {
            var form = forms.resolveItemForm(AccountingObjectCorrectionItem.class, variant, null, null);
            out.append("readonly.CorrectionItem.").append(variant == null ? "default" : variant).append(": ")
                .append(form.getBindingRegistry().getBinding("currentAccountingType").orElseThrow().isReadOnly()).append('\n');
        }
        if (Boolean.getBoolean("form.read.snapshot.write")) {
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/af56/read-requirements-current.txt"), out);
            return;
        }
        try (var resource = getClass().getResourceAsStream("/forms/form-read-dependencies-snapshot.txt")) {
            assertThat(resource).isNotNull();
            assertThat(out.toString()).isEqualTo(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
