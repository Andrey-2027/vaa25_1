package org.ip.views.workspace;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.spring.annotation.SpringComponent;
import org.ipro.form.coordinator.FormNavigator;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.SubsystemNode;
import org.ipro.navigation.NavigationTree;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsReadGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;

@SpringComponent
@Scope("prototype")
public class SubsystemHomeView extends VerticalLayout {

    private final FormNavigator navigator;
    private final RlsReadGate readGate;
    private final RlsCurrentUser currentUser;

    public SubsystemHomeView(@Autowired FormNavigator navigator,
                             @Autowired RlsReadGate readGate,
                             @Autowired RlsCurrentUser currentUser) {
        this.navigator = navigator;
        this.readGate = readGate;
        this.currentUser = currentUser;
        setSizeFull();
        setPadding(true);
        setSpacing(true);
    }

    /**
     * Заполняет вкладку подсистемы. Рабочая область не передаётся: она — UI-scoped бин, и
     * координатор берёт её сам, а прикладной view не знает ни про вкладки, ни про чужой UI.
     */
    public void init(SubsystemNode node) {
        removeAll();

        add(new H3(node.getTitle()));

        var groups = node.getEntityGroupsRecursive();
        if (groups.isEmpty()) {
            add(new H4("В этом разделе пока нет элементов"));
            return;
        }

        boolean first = true;
        for (SubsystemNode.EntityGroup group : groups) {
            if (!(first && group.node() == node)) {
                H4 groupTitle = new H4(group.node().getTitle());
                groupTitle.getStyle().set("margin-top", "1em").set("margin-bottom", "0.25em");
                add(groupTitle);
            }
            first = false;

            FlexLayout tiles = new FlexLayout();
            tiles.setFlexWrap(FlexLayout.FlexWrap.WRAP);
            tiles.getStyle().set("gap", "0.75em");

            for (EntityMetadataInfo entity : group.entities()) {
                if (isEntityVisible(entity)) {
                    tiles.add(createTile(entity));
                }
            }
            add(tiles);
        }
    }

    /**
     * Плитка показывается по решению о <b>чтении</b> — тем же ответом, которым ответит открытие
     * списка ({@link RlsReadGate} спрашивает C5 о чтении сущности) — и больше не по флагу
     * CHECK_ONLY короткого ключа {@code ENTITY:<ИмяКласса>}.
     *
     * <p>Это и был дублирующий гейт C5.7. Короткий ключ — ключ <b>операций</b> типа
     * (CREATE/UPDATE/DELETE, ADR-0017 §3); его «чтение» когда-то означало «виден ли вид целиком»
     * для типа вроде ReceivingDocument, но читал его здесь только UI, а флаг копился в выдаче как
     * второе значение одного и того же ключа. Теперь чтение типа решается один раз — C5 read-ключом
     * {@code ENTITY:<FQN>}, а построчный доступ (JOURNAL/BRANCH) остаётся у RLS. Список меню
     * подсистемы общий: скрывается только то, что действительно нельзя читать, а не то, на что не
     * выдана операция.</p>
     */
    private boolean isEntityVisible(EntityMetadataInfo entity) {
        return readGate.canRead(entity.getEntityClass(), currentUser.requireAuthenticatedUsername());
    }

    /** Uses exactly the projection captured for this UI's menu; no metadata traversal. */
    public void init(NavigationTree.Node node) {
        removeAll();
        add(new H3(node.title()));
        for (var group : node.groupsRecursive()) {
            if (!group.subsystemId().equals(node.id())) {
                H4 title = new H4(group.title());
                title.getStyle().set("margin-top", "1em").set("margin-bottom", "0.25em");
                add(title);
            }
            FlexLayout tiles = new FlexLayout();
            tiles.setFlexWrap(FlexLayout.FlexWrap.WRAP);
            tiles.getStyle().set("gap", "0.75em");
            group.items().forEach(item -> tiles.add(createTile(item.title(), item.icon(), item.entityClass())));
            add(tiles);
        }
    }

    private Button createTile(EntityMetadataInfo entity) {
        return createTile(entity.getListFormTitle(), entity.getIcon(), entity.getEntityClass());
    }

    private Button createTile(String title, String iconName, Class<?> entityClass) {
        Icon icon;
        try {
            icon = VaadinIcon.valueOf(iconName).create();
        } catch (IllegalArgumentException e) {
            icon = VaadinIcon.FILE.create();
        }

        Button tile = new Button(title, icon);
        tile.addThemeVariants(ButtonVariant.LUMO_LARGE);
        tile.getStyle()
            .set("width", "180px")
            .set("height", "90px")
            .set("flex-direction", "column")
            .set("white-space", "normal")
            .set("text-align", "center");

        tile.addClickListener(e -> openEntityList(entityClass));
        return tile;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void openEntityList(Class<?> entityClass) {
        // Координатор выбирает generic ListForm или зарегистрированный variant-View
        // и передаёт ему контекст открытия единым способом.
        navigator.openListForm((Class) entityClass, null, null);
    }
}
