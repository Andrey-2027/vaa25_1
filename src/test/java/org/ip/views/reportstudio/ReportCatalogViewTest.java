package org.ipro.report.ui.catalog;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.upload.Upload;
import org.ipro.jr.service.JrxmlTemplateService;
import org.ipro.report.catalog.ReportCatalogItem;
import org.ipro.report.catalog.ReportCatalogService;
import org.ipro.report.catalog.ReportEngineType;
import org.ipro.report.ui.config.ReportTypeAvailability;
import org.ipro.report.ui.unidr.UniDrCatalogGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R3: каталог — точка входа только для UniDR и JR. UDR-строк, кнопки «UDR —
 * конструктор» и встроенного редактора в нём больше нет.
 *
 * <p>Настройка типов (2026-10-27): окно «Новый отчёт» и строки каталога строятся из
 * {@link ReportTypeAvailability} и наличия портов поставки, поэтому выключенный тип
 * не предлагается ни создать, ни открыть — и не оставляет после себя кнопок, которые
 * отказывают: строки скрыты вместе с типом.</p>
 */
class ReportCatalogViewTest {

    @Test
    void catalogListsJrAndUniDrWithoutUdrEntryPoints() {
        ReportCatalogView view = view(catalog(), new ReportTypeAvailability(true, true), true);

        assertThat(gridNames(view)).containsExactly("Счёт", "Остатки");

        assertThat(button(view, "Новый")).isNotNull();
        assertThat(button(view, "Открыть")).isNotNull();
        assertThat(button(view, "Импорт JSON (UniDR)")).isNotNull();
        assertThat(containsText(view, "UDR — конструктор")).isFalse();
        assertThat(view.offeredTypes()).containsExactly(ReportEngineType.JR, ReportEngineType.UNIDR);
    }

    @Test
    void createWindowOffersOnlyTypesEnabledByConfiguration() {
        assertThat(view(catalog(), new ReportTypeAvailability(false, true), true).offeredTypes())
            .as("JR выключен настройкой app.jrxml.enabled=false")
            .containsExactly(ReportEngineType.UNIDR);

        assertThat(view(catalog(), new ReportTypeAvailability(true, false), true).offeredTypes())
            .as("UniDR выключен настройкой app.unidr.enabled=false")
            .containsExactly(ReportEngineType.JR);

        assertThat(view(catalog(), new ReportTypeAvailability(false, false), true).offeredTypes())
            .as("оба типа выключены: предлагать нечего")
            .isEmpty();
    }

    @Test
    void disabledTypeRowsAreNotListed() {
        assertThat(gridNames(view(catalog(), new ReportTypeAvailability(true, false), true)))
            .as("выключенный тип не оставляет строк, к которым нет ни одного действия")
            .containsExactly("Счёт");
        assertThat(gridNames(view(catalog(), new ReportTypeAvailability(false, true), true)))
            .containsExactly("Остатки");
    }

    @Test
    void unidrImportIsHiddenWhenUnidrIsDisabled() {
        assertThat(importUpload(view(catalog(), new ReportTypeAvailability(true, true), true)).isVisible())
            .isTrue();
        assertThat(importUpload(view(catalog(), new ReportTypeAvailability(true, false), true)).isVisible())
            .as("импорт — второй вход создания UniDR и обходил бы настройку")
            .isFalse();
    }

    @Test
    void disabledTypeNamesTheSwitchThatTurnedItOff() {
        ReportCatalogView view = view(catalog(), new ReportTypeAvailability(false, true), true);

        assertThat(containsText(view, "JR — выключен настройкой app.jrxml.enabled=false"))
            .as("выключенный тип объясняется настройкой, а не выглядит отсутствующим движком")
            .isTrue();
        assertThat(containsText(view, "UniDR — выключен"))
            .as("включённый тип в подсказке не упоминается")
            .isFalse();
    }

    @Test
    void typeWithoutPortsIsNotOfferedEvenWhenEnabledByConfiguration() {
        ReportCatalogView view = view(catalog(), new ReportTypeAvailability(true, true), false);

        assertThat(view.offeredTypes())
            .as("поставка без порта UniDR не показывает тип, хотя настройка его включает")
            .containsExactly(ReportEngineType.JR);
        assertThat(containsText(view, "не подключён в этой поставке")).isTrue();
    }

    private static ReportCatalogService catalog() {
        ReportCatalogService catalog = mock(ReportCatalogService.class);
        when(catalog.findAll(any(), anyBoolean()))
            .thenReturn(List.of(
                new ReportCatalogItem(1L, ReportEngineType.JR, "Счёт", null, true, false),
                new ReportCatalogItem(2L, ReportEngineType.UNIDR, "Остатки", null, true, false)));
        return catalog;
    }

    private static List<String> gridNames(ReportCatalogView view) {
        @SuppressWarnings({"unchecked", "rawtypes"})
        Grid<ReportCatalogItem> grid = (Grid) descendants(view, Grid.class).get(0);
        return grid.getListDataView().getItems().map(ReportCatalogItem::name).toList();
    }

    private static Upload importUpload(ReportCatalogView view) {
        return descendants(view, Upload.class).get(0);
    }

    @SuppressWarnings("rawtypes")
    private static ReportCatalogView view(ReportCatalogService catalog,
                                          ReportTypeAvailability availability,
                                          boolean uniDrPorts) {
        ObjectProvider files = mock(ObjectProvider.class);
        ObjectProvider runs = mock(ObjectProvider.class);
        ObjectProvider jr = mock(ObjectProvider.class);
        when(files.getIfAvailable()).thenReturn(uniDrPorts ? mock(UniDrCatalogGateway.class) : null);
        when(runs.getIfAvailable()).thenReturn(null);
        when(jr.getIfAvailable()).thenReturn(null);
        return new ReportCatalogView(catalog, availability, mock(JrxmlTemplateService.class),
            jr, files, runs);
    }

    private static boolean containsText(Component root, String text) {
        if (root instanceof Button button && text.equals(button.getText())) {
            return true;
        }
        if (root instanceof com.vaadin.flow.component.html.Paragraph paragraph
                && paragraph.getText().contains(text)) {
            return true;
        }
        return root.getChildren().anyMatch(child -> containsText(child, text));
    }

    private static Button button(Component root, String caption) {
        return descendants(root, Button.class).stream()
            .filter(button -> caption.equals(button.getText()))
            .findFirst()
            .orElse(null);
    }

    private static <T extends Component> List<T> descendants(Component root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) {
            result.add(type.cast(root));
        }
        root.getChildren().forEach(child -> result.addAll(descendants(child, type)));
        return result;
    }
}
