package org.ip.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.Query;
import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.Nomenclature;
import org.ip.model.PrdSpecMtr;
import org.ip.model.UnitOfMeasurement;
import org.ipro.form.EntityField;
import org.ipro.form.FieldFactory;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.layout.ItemFormLayout;
import org.ipro.form.builder.layout.Layout;
import org.ipro.metadata.MetadataResolver;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Dynamic parameters use actual lookup SQL, and startup discovers the declarative pilot alone. */
@SpringBootTest(classes = Application.class)
@Transactional
class RelatedFieldsIT {
    @MockitoBean DataInitializer initializer;
    @Autowired EntityManager em;
    @Autowired AccessGrantRepository grants;
    @Autowired MetadataResolver metadata;
    @Autowired FieldFactory fields;
    @Autowired SelectionFormAssembler selections;
    @Autowired ApplicationContext context;
    private UnitOfMeasurement a, b;
    private String prefix;

    @BeforeEach void seed() {
        String user = RlsTestFixture.authenticateAsSuperuser(grants);
        RlsTestFixture.grantC5EntityRead(grants, user, Nomenclature.class, UnitOfMeasurement.class);
        prefix = "af58" + UUID.randomUUID().toString().substring(0, 6);
        a = new UnitOfMeasurement("a" + prefix.substring(4), "Unit A", "c" + prefix.substring(4));
        b = new UnitOfMeasurement("b" + prefix.substring(4), "Unit B", "d" + prefix.substring(4));
        em.persist(a); em.persist(b);
        var first = new Nomenclature(prefix + "a", "First", a); first.setTypeNom("Материал"); em.persist(first);
        var second = new Nomenclature(prefix + "b", "Second", b); second.setTypeNom("Материал"); em.persist(second);
        em.flush(); em.clear();
    }

    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    static class FilterForm extends ItemForm<PrdSpecMtr> {
        @Override protected ItemFormLayout layout(Layout l) {
            return l.of(l.field("unit"), l.field("nomenclature")
                .setSelectionFilter("unitOfMeasurement", l.value("unit")));
        }
    }

    static class RequiredSourceForm extends FilterForm {
        @Override protected void adjust(org.ipro.form.builder.layout.FormAdjust a) {
            a.element("nomenclature").setAllowEmptySelectionSource(false);
        }
    }

    private ItemForm<PrdSpecMtr> form() {
        var form = new FilterForm();
        form.buildDescription(metadata.resolve(PrdSpecMtr.class), fields);
        var row = new PrdSpecMtr(); row.setUnit(a); form.setEntity(row);
        return form;
    }

    @Test
    @SuppressWarnings("unchecked")
    void autocompleteUsesCurrentSourceAndClearsTheOldSelection() {
        var form = form();
        EntityField<Nomenclature> input = form.entityField("nomenclature");
        var text = (TextField) descendants(input).stream().filter(TextField.class::isInstance).findFirst().orElseThrow();
        Grid<Nomenclature> suggestions = (Grid<Nomenclature>) descendants(input).stream()
            .filter(Grid.class::isInstance).findFirst().orElseThrow();
        text.setValue(prefix);
        var first = suggestions.getListDataView().getItems().toList();
        assertThat(first).extracting(Nomenclature::getCode).containsExactly(prefix + "a");
        em.clear();
        assertThat(Hibernate.isInitialized(first.getFirst().getUnitOfMeasurement())).isTrue();
        input.setValue(first.getFirst());
        form.<UnitOfMeasurement>entityField("unit").setValue(b);
        assertThat(input.getValue()).isNull();
        text.setValue(prefix);
        assertThat(suggestions.getListDataView().getItems()).extracting(Nomenclature::getCode)
            .containsExactly(prefix + "b");
        form.entityField("unit").clear();
        text.setValue(prefix + " "); text.setValue(prefix);
        assertThat(suggestions.getListDataView().getItemCount()).isEqualTo(2);
    }

    @Test void oneDialogReevaluatesTheSourceOnEachQuery() {
        var form = form();
        var input = form.entityField("nomenclature");
        var dialog = selections.assemble(Nomenclature.class, selected -> { }, input.getSelectionFilters(),
            selections.resolveColumns(Nomenclature.class), null, input.getLookupPaths());
        dialog.setFixedFilterProvider(input::getSelectionFilters);
        assertThat(dialog.getFilterGrid().getGrid().getDataProvider().fetch(new Query<>()))
            .extracting(Nomenclature::getCode).containsExactly(prefix + "a");
        form.<UnitOfMeasurement>entityField("unit").setValue(b);
        assertThat(dialog.getFilterGrid().getGrid().getDataProvider().fetch(new Query<>()))
            .extracting(Nomenclature::getCode).containsExactly(prefix + "b");
        form.entityField("unit").clear();
        assertThat(dialog.getFilterGrid().getGrid().getDataProvider().fetch(new Query<>()))
            .extracting(Nomenclature::getCode).containsExactlyInAnyOrder(prefix + "a", prefix + "b");
    }

    @Test void declarativePilotHasNoLegacyCustomizationBean() {
        assertThat(context.containsBean("prdSpecMtrFormCustomization")).isFalse();
    }

    @Test void aRequiredMissingSourceProducesAnEmptySqlResult() {
        var form = new RequiredSourceForm();
        form.buildDescription(metadata.resolve(PrdSpecMtr.class), fields);
        form.setEntity(new PrdSpecMtr());
        var input = form.entityField("nomenclature");
        var dialog = selections.assemble(Nomenclature.class, selected -> { }, input.getSelectionFilters(),
            selections.resolveColumns(Nomenclature.class), null, input.getLookupPaths());
        dialog.setFixedFilterProvider(input::getSelectionFilters);
        assertThat(dialog.getFilterGrid().getGrid().getDataProvider().fetch(new Query<>()).count()).isZero();
        form.<UnitOfMeasurement>entityField("unit").setValue(a);
        assertThat(dialog.getFilterGrid().getGrid().getDataProvider().fetch(new Query<>()).count()).isEqualTo(1);
    }

    private static List<Component> descendants(Component root) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(root), root.getChildren()
            .flatMap(child -> descendants(child).stream())).toList();
    }
}
