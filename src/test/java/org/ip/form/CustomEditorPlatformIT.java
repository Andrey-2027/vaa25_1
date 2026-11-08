package org.ip.form;

import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ip.model.Branch;
import org.ip.model.Workshop;
import org.ipro.crud.LookupService;
import org.ipro.crud.ServiceLocator;
import org.ipro.form.*;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builder.*;
import org.ipro.form.builder.layout.FormAdjust;
import org.ipro.form.registry.FormResolver;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.textfield.TextField;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Test-only form variant: no application form or correction migration is required. */
@SpringBootTest(classes = Application.class)
@Import(CustomEditorPlatformIT.Configuration.class)
@Transactional
class CustomEditorPlatformIT {
    @MockitoBean DataInitializer initializer;
    @Autowired FormResolver forms;
    @Autowired FormSaveHandler saveHandler;
    @Autowired ServiceLocator services;
    @Autowired AccessGrantRepository grants;

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {
        @Bean ItemFormCustomization customEditorProbe(LookupService lookup, SelectionFormAssembler selections) {
            return new ItemFormCustomization() {
                public Class<?> entityClass() { return Workshop.class; }
                public void configure(ItemFormVariants variants) {
                    variants.add("test-custom-editor", EditorForm.class, context -> new EditorForm(lookup, selections));
                }
            };
        }
    }

    static class EditorForm extends ItemForm<Workshop> {
        private final LookupService lookup;
        private final SelectionFormAssembler selections;
        int changes;
        ValueChangeSource lastSource;
        EditorForm(LookupService lookup, SelectionFormAssembler selections) {
            this.lookup = lookup; this.selections = selections;
        }
        @Override protected void adjust(FormAdjust a) {
            a.element("code").setEditor(() -> {
                var editor = new TextField(); editor.getElement().setAttribute("data-custom-editor", true); return editor;
            });
            a.element("branch").setEditor(() -> {
                var editor = new EntityField<Branch>("", term -> lookup.search(Branch.class, new String[]{"code", "name"}, term, 20));
                editor.setSelectionFormFactory(onSelect -> selections.assemble(Branch.class, onSelect, Map.of()));
                return editor;
            });
        }
        @Override protected void behavior(FormBehavior b) {
            b.onChange("branch", event -> { changes++; lastSource = event.source(); });
        }
    }

    @BeforeEach void login() {
        String user = RlsTestFixture.authenticateAsSuperuser(grants);
        RlsTestFixture.grantC5EntityRead(grants, user, Workshop.class, Branch.class);
    }
    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void customScalarAndReferenceEditorsUseMetadataAndTheNormalSavePath() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 7);
        var branchDraft = new Branch(); branchDraft.setCode("B" + suffix); branchDraft.setName("Branch " + suffix);
        var branch = services.<Branch, Long>findService(Branch.class).save(branchDraft);
        var form = (EditorForm) forms.resolveItemForm(Workshop.class, "test-custom-editor", null, null);
        form.setSaveHandler(saveHandler); form.initializeNewEntity();
        assertThat(form.getField("code").getElement().hasAttribute("data-custom-editor")).isTrue();
        assertThat(form.getEntityField("code", String.class).isRequiredIndicatorVisible()).isTrue();
        assertThat(form.validate()).anyMatch(message -> message.startsWith("Код:"));
        form.getEntityField("code", String.class).setValue("W" + suffix);
        form.getEntityField("name", String.class).setValue("Workshop " + suffix);
        HasValue<?, Branch> standard = form.getEntityField("branch", Branch.class);
        var events = new ArrayList<Boolean>(); standard.addValueChangeListener(event -> events.add(event.isFromClient()));
        var choose = EntityField.class.getDeclaredMethod("onSelectedInDialog", org.ipro.metadata.HasDisplayName.class);
        choose.setAccessible(true); choose.invoke(form.entityField("branch"), branch);
        assertThat(form.changes).isEqualTo(1); assertThat(form.lastSource).isEqualTo(ValueChangeSource.USER);
        assertThat(events).containsExactly(true); assertThat(form.isDirty()).isTrue();
        assertThat(form.validate()).isEmpty();
        var result = form.save(); assertThat(result).isInstanceOf(FormSaveResult.Success.class);
        var saved = ((FormSaveResult.Success<Workshop>) result).saved();
        assertThat(form.isDirty()).isFalse(); assertThat(form.changes).isEqualTo(1);
        var reopened = (EditorForm) forms.resolveItemForm(Workshop.class, "test-custom-editor", saved.getId(), null);
        reopened.setEntity(services.<Workshop, Long>findService(Workshop.class).findById(saved.getId(), reopened.getReadPaths()).orElseThrow());
        assertThat(reopened.getEntityField("code", String.class).getValue()).isEqualTo("W" + suffix);
        assertThat(reopened.getEntityField("branch", Branch.class).getValue().getId()).isEqualTo(branch.getId());
        assertThat(reopened.isDirty()).isFalse(); assertThat(reopened.changes).isZero();
        reopened.setReadOnly(true);
        assertThat(reopened.getEntityField("code", String.class).isReadOnly()).isTrue();
        assertThat(reopened.getEntityField("branch", Branch.class).isReadOnly()).isTrue();
    }
}
