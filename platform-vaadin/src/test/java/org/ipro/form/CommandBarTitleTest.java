package org.ipro.form;

import com.vaadin.flow.component.icon.VaadinIcon;
import org.ipro.form.action.CrudAction;
import org.ipro.form.builder.ListCommandLayout;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class CommandBarTitleTest {
    @Test void scopedTitleUpdatesButtonMenuTooltipAndCanReturnToDeclaredTitleWithoutLosingIcon() {
        var bar=new CommandBar(); var clicks=new AtomicInteger();
        var command=bar.register(CrudAction.CREATE.id(),"Создать",VaadinIcon.PLUS,clicks::incrementAndGet);
        var layer=ListCommandLayout.builder(); layer.command(CrudAction.CREATE.id()).title("Создать накладную");
        bar.setLayout(layer.build().overlayOn(ListCommandLayout.platformDefaults()));
        assertThat(command.button().getText()).isEqualTo("Создать накладную");
        assertThat(command.menuItem().getText()).isEqualTo("Создать накладную");
        assertThat(command.menuItem().getChildren().filter(com.vaadin.flow.component.icon.Icon.class::isInstance)).hasSize(1);
        command.button().click(); assertThat(clicks).hasValue(1);
        var icons=ListCommandLayout.builder(); icons.command(CrudAction.CREATE.id()).iconOnly();
        bar.setLayout(icons.build().overlayOn(layer.build()).overlayOn(ListCommandLayout.platformDefaults()));
        assertThat(command.button().getText()).isEmpty();
        assertThat(command.button().getElement().getAttribute("aria-label")).isEqualTo("Создать накладную");
        assertThat(command.button().getTooltip().getText()).isEqualTo("Создать накладную");
        bar.setLayout(ListCommandLayout.platformDefaults());
        assertThat(command.button().getText()).isEqualTo("Создать");
        assertThat(command.menuItem().getText()).isEqualTo("Создать");
        assertThat(command.menuItem().getChildren().filter(com.vaadin.flow.component.icon.Icon.class::isInstance)).hasSize(1);
    }
}
