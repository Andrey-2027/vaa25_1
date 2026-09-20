package org.ip.form.registry;

import com.vaadin.flow.component.html.Div;
import org.ip.model.Nomenclature;
import org.ipro.form.builder.ContextFilterField;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormType;
import org.ipro.form.registry.SelectionColumnsDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D3.5.3: реестр форм — снимок конфигурации, а не разделяемая структура.
 *
 * <p>Две половины одного правила. Повторная регистрация одного ключа ломает сборку
 * (см. {@code FormRegistryDuplicateTest}); регистрация <b>после старта</b> контекста запрещена,
 * потому что она меняет набор форм у уже работающих пользователей, гоняется с чтением из UI и не
 * видна в ревью конфигурации. Чтение при этом работает всегда: заморозка закрывает мутаторы, а не
 * реестр.</p>
 */
class FormRegistryLifecycleTest {

    private static void assertBlocked(String what, Runnable mutation) {
        assertThatThrownBy(mutation::run)
            .as(what)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Реестр форм заморожен после старта")
            .hasMessageContaining("ipro.form.registry.allow-runtime-registration");
    }

    @Test
    void frozenRegistryRejectsEveryMutator() {
        FormRegistry registry = new FormRegistry();
        registry.freeze();

        assertThat(registry.isFrozen()).isTrue();
        assertBlocked("фабрика формы", () ->
            registry.registerItemForm(Nomenclature.class, null, ctx -> null, "x"));
        assertBlocked("View-класс списка", () ->
            registry.registerListFormView(Nomenclature.class, null, Div.class, "x"));
        assertBlocked("составной ListForm-view", () ->
            registry.registerListFormView(Nomenclature.class, null, ctx -> null, "x"));
        assertBlocked("набор колонок выбора", () ->
            registry.registerSelectionColumns(Nomenclature.class, null,
                SelectionColumnsDef.of("code"), "x"));
        assertBlocked("контекст-фильтры списка", () ->
            registry.registerContextFilters(Nomenclature.class, List.of(), "x"));
        assertBlocked("контекст-фильтры диалога выбора", () ->
            registry.registerSelectionContextFilters(Nomenclature.class, List.of(), "x"));
        assertBlocked("контекст-фильтры варианта", () ->
            registry.registerVariantContextFilters(Nomenclature.class, FormType.LIST, "v",
                List.of(), "x"));
        assertBlocked("кастомайзер списка", () ->
            registry.addListCustomizer(Nomenclature.class, null, (form, ctx) -> { }));
        assertBlocked("кастомайзер карточки", () ->
            registry.addItemCustomizer(Nomenclature.class, null, (form, ctx) -> { }));
        assertBlocked("отмена регистрации", () ->
            registry.unregister(Nomenclature.class, FormType.ITEM, null));
        assertBlocked("очистка реестра", registry::clear);

        // Заморозка не мешает читать и идемпотентна: Vaadin dev-mode может обновить контекст.
        registry.freeze();
        assertThat(registry.getContextFilters(Nomenclature.class)).isEmpty();
        assertThat(registry.findItemForm(Nomenclature.class, null)).isNull();
    }

    /**
     * {@code unregister} снимает ключ целиком. Раньше он чистил только карту фабрик формы, поэтому
     * составной View того же ключа оставался, а следующий {@code register*} падал на «дубликате» с
     * источником уже снятой регистрации.
     */
    @Test
    void unregisterRemovesTheWholeKeySoReRegistrationSucceeds() {
        FormRegistry registry = new FormRegistry();
        registry.registerListForm(Nomenclature.class, "v", ctx -> null, "forms-source");
        registry.registerListFormView(Nomenclature.class, "v", ctx -> null, "view-source");
        assertThat(registry.registrationsOf(Nomenclature.class)).isNotEmpty();

        registry.unregister(Nomenclature.class, FormType.LIST, "v");

        assertThat(registry.findListForm(Nomenclature.class, "v")).isNull();
        assertThat(registry.getListFormViewFactory(Nomenclature.class, "v")).isNull();
        assertThat(registry.registrationsOf(Nomenclature.class)).isEmpty();

        // повторная регистрация того же ключа — обычная регистрация, а не конфликт с призраком
        registry.registerListForm(Nomenclature.class, "v", ctx -> null, "again");
        assertThat(registry.findListForm(Nomenclature.class, "v")).isNotNull();
    }
}
