package org.ip.views.reportstudio;

import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import org.ip.views.test.ReportQueryPreviewView;
import org.ipro.reportstudio.query.ReportPreviewService;
import org.ipro.reportstudio.query.ReportQueryGuard;

/**
 * Первый вертикальный срез пользовательского редактора отчётов.
 *
 * <p>Экран отделяет метаданные шаблона от редактирования JPQL и переиспользует
 * уже проверенный контур {@link ReportQueryPreviewView}: проверка SELECT-only,
 * сопоставление параметров, RLS entity-access и транзакционный предпросмотр.
 * Сохранение шаблона, настройка бэндов и запуск экспорта будут добавлены
 * следующими инкрементами фазы UI.</p>
 */
@Route("report-editor")
@PageTitle("Редактор отчёта")
@PermitAll
public class ReportEditorView extends VerticalLayout {

    private final TextField name = new TextField("Наименование отчёта");
    private final TextArea description = new TextArea("Описание");

    public ReportEditorView(ReportQueryGuard guard, ReportPreviewService previewService) {
        setSizeFull();
        setPadding(true);
        setSpacing(true);

        add(new H2("Мини-редактор отчётов"));
        add(new Paragraph(
                "Задайте метаданные шаблона и JPQL-запрос. Перед предпросмотром "
                        + "запрос проходит обязательные проверки безопасности и RLS."));

        FormLayout metadata = new FormLayout();
        metadata.setWidthFull();
        name.setRequiredIndicatorVisible(true);
        name.setMaxLength(250);
        name.setWidthFull();
        description.setMaxLength(2_000);
        description.setWidthFull();
        description.setMinHeight("7em");
        metadata.add(name, description);
        metadata.setColspan(description, 2);
        add(metadata);

        ReportQueryPreviewView queryPreview = new ReportQueryPreviewView(guard, previewService);
        queryPreview.setPadding(false);
        queryPreview.setSpacing(true);
        queryPreview.setWidthFull();

        Details querySection = new Details("JPQL-запрос и предпросмотр", queryPreview);
        querySection.setOpened(true);
        querySection.setWidthFull();
        add(querySection);
    }

    public String reportName() {
        return name.getValue();
    }

    public String reportDescription() {
        return description.getValue();
    }
}
