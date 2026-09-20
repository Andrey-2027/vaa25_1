package org.ip.form.registry;

import org.ip.model.Nomenclature;
import org.ip.model.Workshop;
import org.junit.jupiter.api.Test;

import com.vaadin.flow.component.html.Div;
import org.ipro.form.builder.ContextFilterField;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormType;
import org.ipro.form.registry.SelectionColumnsDef;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D3.5.3: повторная регистрация одного ключа — fail-fast, а не тихое last-wins.
 *
 * <p>D3.5.3-fix: правило действует на <b>всех</b> путях регистрации. Раньше
 * {@code registerSelectionColumns}, {@code registerContextFilters},
 * {@code registerSelectionContextFilters}, {@code registerVariantContextFilters} и
 * без источниковая перегрузка {@code registerListFormView} писали через {@code put} — то есть
 * ровно те пути, которыми пользуются регистраторы приложения, оставались полем для той самой
 * ошибки «открылась не та форма».</p>
 */
class FormRegistryDuplicateTest {

    @Test
    void duplicateFactoryRegistrationFailsFast() {
        FormRegistry registry = new FormRegistry();
        registry.registerItemForm(Nomenclature.class, null, ctx -> null, "first");

        assertThatThrownBy(() -> registry.registerItemForm(Nomenclature.class, null, ctx -> null, "second"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Дубликат регистрации формы")
            .hasMessageContaining("first")
            .hasMessageContaining("second");
    }

    @Test
    void differentVariantsDoNotClash() {
        FormRegistry registry = new FormRegistry();
        registry.registerItemForm(Nomenclature.class, null, ctx -> null, "first");
        registry.registerItemForm(Nomenclature.class, "compact", ctx -> null, "second");
        registry.registerItemForm(Workshop.class, null, ctx -> null, "third");
    }

    @Test
    void duplicateListFormViewFailsFast() {
        FormRegistry registry = new FormRegistry();
        registry.registerListFormView(Nomenclature.class, null, ctx -> null, "first");

        assertThatThrownBy(() -> registry.registerListFormView(Nomenclature.class, null, ctx -> null, "second"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Дубликат регистрации составного ListForm-view");
    }

    /** Без источниковая перегрузка — тот же ключ и то же правило, а не тихая перезапись. */
    @Test
    void duplicateListFormViewWithoutSourceFailsFastToo() {
        FormRegistry registry = new FormRegistry();
        registry.registerListFormView(Nomenclature.class, "compact", ctx -> null);

        assertThatThrownBy(() -> registry.registerListFormView(Nomenclature.class, "compact", ctx -> null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Дубликат регистрации составного ListForm-view")
            .hasMessageContaining("<без источника>");
    }

    @Test
    void duplicateViewClassFailsFast() {
        FormRegistry registry = new FormRegistry();
        registry.registerListFormView(Workshop.class, null, Div.class, "first");

        assertThatThrownBy(() -> registry.registerListFormView(Workshop.class, null, Div.class, "second"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Дубликат регистрации ListForm-view")
            .hasMessageContaining(Div.class.getName());
    }

    @Test
    void duplicateSelectionColumnsFailFast() {
        FormRegistry registry = new FormRegistry();
        registry.registerSelectionColumns(Nomenclature.class, "compact",
            SelectionColumnsDef.of("code"));

        assertThatThrownBy(() -> registry.registerSelectionColumns(Nomenclature.class, "compact",
            SelectionColumnsDef.of("name")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Дубликат регистрации набора колонок выбора");
    }

    @Test
    void duplicateContextFiltersFailFast() {
        FormRegistry registry = new FormRegistry();
        registry.registerContextFilters(Nomenclature.class,
            List.of(ContextFilterField.auto("code", "Код")), "first");

        assertThatThrownBy(() -> registry.registerContextFilters(Nomenclature.class,
            List.of(ContextFilterField.auto("name", "Наименование")), "second"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Дубликат регистрации контекст-фильтров списка")
            .hasMessageContaining(Nomenclature.class.getSimpleName())
            .hasMessageContaining("first");
    }

    @Test
    void duplicateSelectionContextFiltersFailFast() {
        FormRegistry registry = new FormRegistry();
        registry.registerSelectionContextFilters(Nomenclature.class,
            List.of(ContextFilterField.auto("code", "Код")), "first");

        assertThatThrownBy(() -> registry.registerSelectionContextFilters(Nomenclature.class,
            List.of(ContextFilterField.auto("name", "Наименование")), "second"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Дубликат регистрации контекст-фильтров диалога выбора");
    }

    @Test
    void duplicateVariantContextFiltersFailFast() {
        FormRegistry registry = new FormRegistry();
        registry.registerVariantContextFilters(Nomenclature.class, FormType.LIST, "v",
            List.of(ContextFilterField.auto("code", "Код")), "first");

        assertThatThrownBy(() -> registry.registerVariantContextFilters(Nomenclature.class,
            FormType.LIST, "v", List.of(ContextFilterField.auto("name", "Наименование")), "second"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Дубликат регистрации контекст-фильтров варианта");
    }

    /**
     * Один и тот же ключ в двух картах (фабрика списка и его составной View) — не дубликат:
     * это два уровня одной регистрации, они снимаются вместе (см. FormRegistryLifecycleTest).
     */
    @Test
    void sameKeyInDifferentMapsIsNotADuplicate() {
        FormRegistry registry = new FormRegistry();
        registry.registerListForm(Nomenclature.class, "v", ctx -> null, "forms");
        registry.registerListFormView(Nomenclature.class, "v", ctx -> null, "view");

        assertThat(registry.findListForm(Nomenclature.class, "v")).isNotNull();
        assertThat(registry.getListFormViewFactory(Nomenclature.class, "v")).isNotNull();
    }
}
