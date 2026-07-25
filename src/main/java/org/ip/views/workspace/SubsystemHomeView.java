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
import org.ip.form.coordinator.FormCoordinator;
import org.ip.form.coordinator.ListFormWrapper;
import org.ip.metadata.EntityMetadataInfo;
import org.ip.metadata.SubsystemNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;

@SpringComponent
@Scope("prototype")
public class SubsystemHomeView extends VerticalLayout {

    private final FormCoordinator coordinator;

    public SubsystemHomeView(@Autowired FormCoordinator coordinator) {
        this.coordinator = coordinator;
        setSizeFull();
        setPadding(true);
        setSpacing(true);
    }

    public void init(SubsystemNode node, Workspace workspace) {
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
                tiles.add(createTile(entity, workspace));
            }
            add(tiles);
        }
    }

    private Button createTile(EntityMetadataInfo entity, Workspace workspace) {
        Icon icon;
        try {
            icon = VaadinIcon.valueOf(entity.getIcon()).create();
        } catch (IllegalArgumentException e) {
            icon = VaadinIcon.FILE.create();
        }

        Button tile = new Button(entity.getListFormTitle(), icon);
        tile.addThemeVariants(ButtonVariant.LUMO_LARGE);
        tile.getStyle()
            .set("width", "180px")
            .set("height", "90px")
            .set("flex-direction", "column")
            .set("white-space", "normal")
            .set("text-align", "center");

        tile.addClickListener(e -> openEntityList(entity, workspace));
        return tile;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void openEntityList(EntityMetadataInfo entity, Workspace workspace) {
        Class entityClass = entity.getEntityClass();
        String tabId = "entity-list-" + entityClass.getSimpleName();
        workspace.open(ListFormWrapper.class, tabId, entity.getListFormTitle(),
            (ListFormWrapper wrapper) -> wrapper.setContent(coordinator.createListForm(entityClass)));
    }
}
