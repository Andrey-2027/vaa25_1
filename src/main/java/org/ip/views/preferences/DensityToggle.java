package org.ip.views.preferences;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;

/**
 * Компактный переключатель плотности UI: «обычная ↔ компактная».
 *
 * <p>Сохраняет выбор в {@link UserPreferencesStore} (серверный кеш сессии +
 * client-side localStorage). Применяется мгновенно через CSS-переменную
 * {@code --lumo-density-mode}.
 *
 * <p>Использовать: положить в хедер {@code AppLayout} рядом с другими
 * управляющими элементами.
 */
public class DensityToggle extends Button {

    private final UserPreferencesStore store;

    public DensityToggle(UserPreferencesStore store) {
        super();
        this.store = store;

        addClassNames("density-toggle");
        addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ICON);
        updateIcon(store.get().density());
        updateTooltip(store.get().density());

        addClickListener(e -> {
            Density current = store.get().density();
            Density next = current == Density.COMPACT ? Density.COMFORTABLE : Density.COMPACT;
            store.setDensity(next);
            applyClientSide(next);
            updateIcon(next);
            updateTooltip(next);
        });

        addAttachListener(e -> {
            UI ui = e.getUI();
            store.initFromClient(ui);
            ui.getPage().executeJs(DensityJs.BOOTSTRAP);
        });
    }

    private void applyClientSide(Density density) {
        UI ui = UI.getCurrent();
        if (ui == null) return;
        ui.getPage().executeJs(DensityJs.APPLY_DENSITY, density.getThemeValue());
    }

    private void updateIcon(Density density) {
        // COMPACT → иконка "сжать"; COMFORTABLE → иконка "расширить"
        Icon icon = density == Density.COMPACT
                ? new Icon(VaadinIcon.COMPRESS_SQUARE)
                : new Icon(VaadinIcon.EXPAND_SQUARE);
        setIcon(icon);
    }

    private void updateTooltip(Density density) {
        String text = density == Density.COMPACT
                ? "Размер: компактный (нажмите для обычного)"
                : "Размер: обычный (нажмите для компактного)";
        setTooltipText(text);
    }
}
