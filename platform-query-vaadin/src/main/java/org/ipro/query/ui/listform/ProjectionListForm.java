package org.ipro.query.ui.listform;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.menubar.MenuBar;
import com.vaadin.flow.component.menubar.MenuBarVariant;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.ipro.filtergrid.projection.CompositeRowKey;
import org.ipro.query.ui.grid.QueryGridEnvironment;
import org.ipro.query.ui.grid.QueryGridPanel;
import org.ipro.query.ui.grid.QueryGridSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Оболочка списка по запросу: внешне как ListForm — заголовок, отдельный toolbar над сеткой, сетка
 * ядра просмотра. Добавляет к ядру «Виды», выбор строки и место для команд над объектом (FQ-7).
 *
 * <p>Toolbar (UX-1): команды над объектом (приходят через порт приложения и встают в начало), затем
 * «Обновить» и «Виды», справа «Ещё» → «Колонки…», «Группировка», «Итоги…», «Скопировать ссылку».
 * Встроенный вид (UX-10) — без заголовка и панели параметров, в toolbar только «Обновить» и «Ещё».</p>
 */
public class ProjectionListForm extends VerticalLayout {
    private final QueryGridPanel panel;
    private final QueryListOptions options;
    private final String formKey;
    private final HorizontalLayout commandSlot = new HorizontalLayout();
    private final HorizontalLayout toolbar = new HorizontalLayout();
    private final List<Consumer<Optional<CompositeRowKey>>> selectionListeners = new ArrayList<>();
    private final QueryViewsController viewsController;
    private Button viewsButton;
    private CompositeRowKey selected;

    /**
     * @param listKey ключ списка (не ревизия запуска): по нему и варианту строится ключ «Видов»
     */
    public ProjectionListForm(QueryGridEnvironment env, QueryGridSpec spec, String listKey,
                              QueryListOptions options) {
        this.options = options == null ? QueryListOptions.DEFAULT : options;
        this.panel = new QueryGridPanel(env, spec);
        this.formKey = this.options.formKey(java.util.Objects.requireNonNull(listKey, "listKey"));
        setPadding(false);
        setSpacing(false);
        setSizeFull();

        if (!this.options.embedded()) {
            Span title = new Span(spec.title());
            title.getStyle().set("font-weight", "600").set("font-size", "var(--lumo-font-size-l)")
                    .set("padding", "var(--lumo-space-s) var(--lumo-space-m) 0");
            add(title);
        } else {
            panel.setParametersVisible(false);
        }
        buildToolbar();
        add(toolbar, panel);
        setFlexGrow(1, panel);

        viewsController = this.options.views() == null ? null
                : new QueryViewsController(panel, this.options.views(), formKey);
        if (viewsController != null) {
            try {
                viewsController.applyDefault();
            } catch (RuntimeException unavailable) {
                // вид по умолчанию не должен мешать открыть список: остаётся стандартный вид
            }
        }
        panel.addGridListener(grid -> {
            selected = null;
            grid.ifPresent(value -> value.setSelectionHandler(key -> {
                selected = key;
                fireSelection();
            }));
            fireSelection();
        });
    }

    // -- публичный API -----------------------------------------------------

    /** Ядро просмотра: параметры, значения привязок, запуск. */
    public QueryGridPanel panel() {
        return panel;
    }

    /** Ключ формы для «Видов»: {@code querylist.<ключ списка>[.<вариант>]}. */
    public String formKey() {
        return formKey;
    }

    /** Строка, выбранная в сетке; пусто без выбора и в списке без ключа строки. */
    public Optional<CompositeRowKey> selectedRow() {
        return Optional.ofNullable(selected);
    }

    public void addSelectionListener(Consumer<Optional<CompositeRowKey>> listener) {
        selectionListeners.add(listener);
    }

    /** Команда над объектом (FQ-7) встаёт в начало toolbar. */
    public void addCommand(Component command) {
        commandSlot.add(command);
        commandSlot.setVisible(true);
    }

    /** Кнопки toolbar по подписям — для проверок и тем, кто расширяет оболочку. */
    public List<String> toolbarLabels() {
        List<String> labels = new ArrayList<>();
        toolbar.getChildren().forEach(child -> {
            if (child instanceof Button button && !button.getText().isBlank()) labels.add(button.getText());
            if (child instanceof MenuBar menu) menu.getItems().forEach(item -> labels.add(item.getText()));
        });
        return labels;
    }

    /** Пункты меню «Ещё» по подписям. */
    public List<String> moreItems() {
        List<String> labels = new ArrayList<>();
        toolbar.getChildren().filter(MenuBar.class::isInstance).map(MenuBar.class::cast).findFirst()
                .flatMap(menu -> menu.getItems().stream().findFirst())
                .ifPresent(more -> more.getSubMenu().getItems().forEach(item -> labels.add(item.getText())));
        return labels;
    }

    public void openViews() {
        if (viewsController != null) new QueryViewsDialog(panel.spec().title(), viewsController).open();
    }

    // -- toolbar -----------------------------------------------------------

    private void buildToolbar() {
        toolbar.setWidthFull();
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);
        toolbar.getStyle().set("padding", "var(--lumo-space-xs) var(--lumo-space-m)")
                .set("border-bottom", "1px solid var(--lumo-contrast-10pct)");
        commandSlot.setPadding(false);
        commandSlot.setVisible(false);
        toolbar.add(commandSlot);

        Button refresh = new Button("Обновить", new Icon(VaadinIcon.REFRESH), event -> panel.refresh());
        refresh.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        toolbar.add(refresh);
        if (options.views() != null && !options.embedded()) {
            viewsButton = new Button("Виды", new Icon(VaadinIcon.LIST), event -> openViews());
            viewsButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            toolbar.add(viewsButton);
        }
        Div spacer = new Div();
        toolbar.add(spacer);
        toolbar.setFlexGrow(1, spacer);

        MenuBar more = new MenuBar();
        more.addThemeVariants(MenuBarVariant.LUMO_TERTIARY, MenuBarVariant.LUMO_SMALL);
        var item = more.addItem("Ещё");
        item.getSubMenu().addItem("Колонки…", event -> panel.openColumnsDialog());
        item.getSubMenu().addItem("Группировка", event -> panel.setGroupingOpen(!panel.isGroupingOpen()));
        item.getSubMenu().addItem("Итоги…", event -> panel.openTotalsDialog());
        if (options.link() != null) {
            item.getSubMenu().addItem("Скопировать ссылку", event -> copyLink());
        }
        toolbar.add(more);
    }

    private void copyLink() {
        String url = options.link().get();
        getUI().ifPresent(ui -> ui.getPage().executeJs("navigator.clipboard.writeText($0)", url));
        Notification.show("Ссылка скопирована");
    }

    private void fireSelection() {
        for (var listener : List.copyOf(selectionListeners)) listener.accept(Optional.ofNullable(selected));
    }
}
