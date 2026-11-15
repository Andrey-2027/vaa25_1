package org.ip.views.admin.menu;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.html.UnorderedList;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.server.streams.UploadEvent;
import com.vaadin.flow.server.streams.UploadHandler;
import org.ipro.navigation.NavigationConflictException;
import org.ipro.navigation.NavigationMenu;
import org.ipro.navigation.NavigationMenuService;

import java.io.IOException;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

final class MenuImportDialog extends Dialog {
    private final Div details = new Div();
    private final Span status = new Span();
    private final TextField name = new TextField("Название меню");
    private final Button apply = new Button("Применить");
    private final Upload upload;
    private MenuJson.Preview preview;

    MenuImportDialog(NavigationMenuService service, NavigationMenu target, LongConsumer applied) {
        setHeaderTitle(target == null ? "Загрузить как новое меню" : "Заменить меню «" + target.name() + "»");
        setWidth("min(46rem, 95vw)");
        name.setMaxLength(120);
        name.setVisible(false);
        apply.setEnabled(false);
        upload = new Upload(new UploadHandler() {
            @Override public long getFileSizeMax() { return MenuJson.MAX_BYTES; }
            @Override public long getRequestSizeMax() { return MenuJson.MAX_BYTES + 65_536; }
            @Override public long getFileCountMax() { return 1; }

            @Override public void handleUploadRequest(UploadEvent event) throws IOException {
                byte[] bytes = event.getInputStream().readNBytes(MenuJson.MAX_BYTES + 1);
                event.getUI().accessSynchronously(() -> {
                    if (!isOpened()) return;
                    clearPreview();
                    try {
                        var file = MenuJson.decode(bytes);
                        var current = target == null ? null : service.structure(target.id());
                        var known = service.functions().stream().map(row -> row.function().key()).collect(Collectors.toSet());
                        preview = MenuJson.preview(file, current, known);
                        if (target == null) {
                            name.setValue(preview.name());
                            name.setVisible(true);
                        }
                        showPreview(preview);
                        apply.setEnabled(true);
                    } catch (IllegalArgumentException | org.springframework.security.access.AccessDeniedException failure) {
                        status.setText(failure.getMessage());
                        event.reject("Файл меню не принят");
                    }
                });
            }
        });
        upload.setMaxFiles(1);
        upload.setMaxFileSize(MenuJson.MAX_BYTES);
        upload.setAcceptedFileTypes(".json", "application/json");
        upload.setUploadButton(new Button("Выбрать JSON"));
        upload.addAllFinishedListener(event -> upload.clearFileList());
        upload.addFileRejectedListener(event -> {
            clearPreview();
            status.setText("Выберите файл JSON размером до 1 МиБ");
        });
        apply.addClickListener(event -> {
            if (preview == null) return;
            long id;
            try {
                var structure = preview.structure();
                if (target == null) {
                    id = service.importMenu(name.getValue(), structure).id();
                } else {
                    id = service.replaceMenu(target.id(), structure.version(), structure).menuId();
                }
            } catch (NavigationConflictException conflict) {
                status.setText(target == null ? "Имя меню уже занято. Укажите другое имя."
                    : "Меню изменилось. Загрузите файл снова для нового предпросмотра.");
                if (target != null) apply.setEnabled(false);
                return;
            } catch (IllegalArgumentException | org.springframework.security.access.AccessDeniedException failure) {
                status.setText(failure.getMessage());
                return;
            }
            close();
            applied.accept(id);
        });
        status.getElement().setAttribute("role", "status");
        add(upload, name, status, details);
        getFooter().add(new Button("Отмена", event -> close()), apply);
    }

    private void clearPreview() {
        preview = null;
        apply.setEnabled(false);
        details.removeAll();
        status.setText("");
        name.setVisible(false);
    }

    private void showPreview(MenuJson.Preview result) {
        details.add(new H4("Изменения"));
        if (result.changes().isEmpty()) details.add(new Span("Состав меню совпадает"));
        else details.add(new UnorderedList(result.changes().stream().map(ListItem::new).toArray(ListItem[]::new)));
        if (!result.unknown().isEmpty()) {
            details.add(new H4("Будут пропущены пункты с неизвестными функциями"),
                new UnorderedList(result.unknown().stream().map(ListItem::new).toArray(ListItem[]::new)));
        }
    }
}
