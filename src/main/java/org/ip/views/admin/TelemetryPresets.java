package org.ip.views.admin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.function.Consumer;

import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;

import org.ipro.filtergrid.DateRangeFilter;

/**
 * Временные пресеты журнала диагностики: Час / Сегодня / Вчера / 7 дней /
 * 30 дней / Всё. Заполняют {@link DateRangeFilter} грида и уведомляют
 * слушателя (тот перечитывает данные).
 */
final class TelemetryPresets extends HorizontalLayout {

    enum Preset {
        TODAY("Сегодня"),
        YESTERDAY("Вчера"),
        DAYS7("7 дней"),
        DAYS30("30 дней"),
        ALL("Всё");

        private final String label;

        Preset(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private final DateRangeFilter<?> dateRange;
    private final Consumer<Preset> onChange;
    private Preset current;

    TelemetryPresets(DateRangeFilter<?> dateRange, Consumer<Preset> onChange) {
        this.dateRange = dateRange;
        this.onChange = onChange;
        setPadding(false);
        setSpacing(false);
        setAlignItems(FlexComponent.Alignment.CENTER);

        RadioButtonGroup<Preset> buttons = new RadioButtonGroup<>();
        buttons.setItems(Preset.values());
        buttons.setItemLabelGenerator(p -> p.label());
        buttons.addThemeName("small");
        buttons.getElement().getStyle().set("display", "flex");
        buttons.getElement().getStyle().set("flex-direction", "row");
        buttons.getElement().getStyle().set("gap", "8px");
        buttons.addValueChangeListener(e -> {
            if (e.getValue() != null) {
                apply(e.getValue());
            }
        });
        buttons.setValue(Preset.DAYS7);

        add(new Icon(VaadinIcon.CLOCK), buttons);
    }

    /** Текущий выбранный пресет (для панелей, считающих период сами). */
    Preset current() {
        return current;
    }

    /** Применить пресет: заполнить DateRangeFilter и уведомить слушателя. */
    void apply(Preset preset) {
        current = preset;
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        LocalDate from;
        LocalDate to = null; // null = «по сейчас»
        switch (preset) {
            case TODAY -> from = today;
            case YESTERDAY -> {
                from = today.minusDays(1);
                to = today.minusDays(1);
            }
            case DAYS7 -> from = today.minusDays(7);
            case DAYS30 -> from = today.minusDays(30);
            case ALL -> from = null;
            default -> from = today;
        }
        dateRange.getDateFrom().setValue(from);
        dateRange.getDateTo().setValue(to);
        if (onChange != null) {
            onChange.accept(preset);
        }
    }
}
