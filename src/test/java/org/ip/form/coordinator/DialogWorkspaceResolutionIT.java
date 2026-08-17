package org.ip.form.coordinator;

import org.ip.config.DataInitializer;
import org.ip.form.builtin.ItemForm;
import org.ip.form.registry.FormRegistry;
import org.ip.form.registry.FormResolver;
import org.ip.form.registry.FormType;
import org.ip.model.Workshop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Паритет Dialog / Workspace (PR-0.5): одна и та же (entityClass, variant) через
 * {@link FormCoordinator#openItemFormAsDialog} (private) и через
 * {@link ItemFormWrapperView#init} (Workspace-вкладку) должна давать одну и ту же
 * форму: тот же класс ItemForm (generic / кастомная), тот же набор табличных частей
 * и одинаковую инициализацию новой записи (id == null).
 */
@SpringBootTest
class DialogWorkspaceResolutionIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private FormResolver formResolver;

    @Autowired
    private FormRegistry formRegistry;

    @Autowired
    private ObjectProvider<ItemFormWrapperView> viewProvider;

    private String registeredVariant;

    @AfterEach
    void cleanup() {
        if (registeredVariant != null) {
            formRegistry.unregister(Workshop.class, FormType.ITEM, registeredVariant);
            registeredVariant = null;
        }
    }

    @Test
    void genericFormResolvesEquallyForDialogAndWorkspace() {
        ItemForm<Workshop> dialogForm = formResolver.resolveItemForm(Workshop.class, null, null, null);
        dialogForm.initializeNewEntity();

        ItemFormWrapperView view = viewProvider.getObject();
        view.init(Workshop.class, null, null, saved -> {
        }, () -> {
        });
        ItemForm<?> workspaceForm = view.getItemForm();

        assertThat(workspaceForm).isNotNull();
        // тот же класс (generic ItemForm) и тот же состав табличных частей
        assertThat(workspaceForm.getClass()).isEqualTo(dialogForm.getClass());
        assertThat(workspaceFormTableSectionClasses(workspaceForm))
            .isEqualTo(dialogFormTableSectionClasses(dialogForm));
        // обе ветки для id == null инициализируют новую запись
        assertThat(workspaceForm.getEntity()).isNotNull();
        assertThat(workspaceForm.getEntity().getId()).isNull();
        assertThat(dialogForm.getEntity()).isNotNull();
        assertThat(dialogForm.getEntity().getId()).isNull();
    }

    @Test
    void namedVariantResolvesEquallyForDialogAndWorkspace() {
        registeredVariant = "it-brief-" + UUID.randomUUID().toString().substring(0, 8);
        formRegistry.registerItemForm(Workshop.class, registeredVariant,
            ctx -> new ItemForm<>(ctx.metadataResolver().resolve(Workshop.class), ctx.fieldFactory()));

        ItemForm<Workshop> dialogForm =
            formResolver.resolveItemForm(Workshop.class, registeredVariant, null, null);
        dialogForm.initializeNewEntity();

        ItemFormWrapperView view = viewProvider.getObject();
        view.init(Workshop.class, registeredVariant, null, saved -> {
        }, () -> {
        });
        ItemForm<?> workspaceForm = view.getItemForm();

        assertThat(workspaceForm).isNotNull();
        assertThat(workspaceForm.getClass()).isEqualTo(dialogForm.getClass());
        assertThat(workspaceFormTableSectionClasses(workspaceForm))
            .isEqualTo(dialogFormTableSectionClasses(dialogForm));
        assertThat(workspaceForm.getEntity()).isNotNull();
        assertThat(workspaceForm.getEntity().getId()).isNull();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static java.util.List<Class<?>> dialogFormTableSectionClasses(ItemForm<?> form) {
        return ((ItemForm) form).getTableSections().stream()
            .map(sec -> sec.getClass())
            .toList();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static java.util.List<Class<?>> workspaceFormTableSectionClasses(ItemForm<?> form) {
        return ((ItemForm) form).getTableSections().stream()
            .map(sec -> sec.getClass())
            .toList();
    }
}
