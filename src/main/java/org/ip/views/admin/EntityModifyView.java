package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.server.streams.DownloadHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.ipro.design.ConstraintDraft;
import org.ipro.design.DraftProblem;
import org.ipro.design.DraftText;
import org.ipro.design.FieldApplications;
import org.ipro.design.FieldDraft;
import org.ipro.design.ReferenceDraft;
import org.ipro.design.existing.ModificationPlan;
import org.ipro.design.source.ModificationApply;
import org.ipro.design.source.ModificationPlanner;
import org.ipro.design.source.PlanDirectory;
import org.ipro.design.source.SourceRefusal;
import org.ipro.design.source.UnifiedDiff;
import org.ipro.form.Dirtyable;

/**
 * Changes an existing entity from its card: shows the fields of the file (a label can be changed), the fields the tool does not
 * understand (shown, never changed), lets new fields be added, and shows the plan as a diff and SQL before anything is written.
 * Writing needs {@code app.entity-draft.allow-source-write}; without it the plan can only be downloaded.
 * One view belongs to one principal and one class; it never touches the disk except through {@link EntityModificationService}.
 */
public class EntityModifyView extends Div implements Dirtyable {
    private static final List<String> TYPES = List.of("STRING", "INTEGER", "LONG", "DECIMAL", "BOOLEAN", "DATE", "DATETIME", "REFERENCE");

    private final EntityDraftAccess access;
    private final EntityModificationService service;
    private ModifySession session;
    private String owner;
    private Class<?> type;
    private Runnable changed = () -> {};
    private ModificationPlan plan;
    private long planRevision = -1;
    private final Span heading = new Span(), notice = new Span();
    private final Div fileRows = new Div(), lockedRows = new Div(), newRows = new Div(), result = new Div();
    private final Button addField = new Button("+ Реквизит", e -> command(() -> { session.addField("STRING"); invalidate(); renderNew(); }));
    private final Button show = new Button("Показать изменения", e -> command(this::preview));
    private final Button apply = new Button("Применить", e -> command(this::confirmApply));
    private final Anchor download = new Anchor();

    public EntityModifyView(EntityDraftAccess access, EntityModificationService service) {
        this.access = access; this.service = service;
        addClassNames("entity-explorer-pilot", "entity-modify");
        setSizeFull();
        heading.addClassName("entity-modify-heading");
        notice.getElement().setAttribute("data-modify-notice", "");
        result.getElement().setAttribute("data-modify-result", "");
        fileRows.getElement().setAttribute("data-modify-file-fields", "");
        lockedRows.getElement().setAttribute("data-modify-locked-fields", "");
        newRows.getElement().setAttribute("data-modify-new-fields", "");
        show.getElement().setAttribute("data-modify-show", "");
        apply.getElement().setAttribute("data-modify-apply", "");
        apply.setVisible(false); download.setVisible(false);
        download.setText("Скачать план"); download.getElement().setAttribute("download", "entity-modification-plan.zip");
        add(heading, notice, fileRows, lockedRows, newRows, new Div(addField, show, apply, download), result);
    }

    /** Called by the workspace once, with the class of the card that was open. */
    void initialize(Class<?> type, Runnable changed) {
        this.type = Objects.requireNonNull(type); this.changed = Objects.requireNonNull(changed);
        activate();
    }

    /** Reads the file again for a principal that differs from the one that opened the view. */
    void activate() {
        String principal = access.principal();
        if (!service.available() || principal == null) { revoke(); return; }
        if (session != null && principal.equals(owner)) { changed.run(); return; }
        owner = principal; plan = null; planRevision = -1;
        try {
            session = service.open(type);
        } catch (EntityModificationService.ModificationRefused refusal) {
            session = null; showRefusal(refusal.refusal()); changed.run(); return;
        }
        render();
        changed.run();
    }

    private void revoke() {
        session = null; owner = null; plan = null;
        removeAll(); add(new Span("Изменение недоступно"));
    }

    String tabTitle() { return "Изменение · " + (type == null ? "" : type.getSimpleName()) + (isDirty() ? " *" : ""); }
    ModifySession session() { return session; }
    ModificationPlan plan() { return plan; }

    @Override public boolean isDirty() { return session != null && session.changed(); }
    @Override public String getCloseConfirmMessage() { return "Изменения реквизитов не применены. Закрыть вкладку?"; }

    // ------------------------------------------------------------------ rendering

    private boolean permitted() {
        if (!service.available() || session == null || !Objects.equals(owner, access.principal())) { activate(); return false; }
        return true;
    }

    private void command(Runnable action) { if (permitted()) action.run(); }

    private void render() {
        heading.setText("Изменение · " + type.getSimpleName() + " · " + session.base().relativePath());
        String why = session.unsupported();
        notice.setText(why);
        addField.setVisible(why.isEmpty()); show.setVisible(why.isEmpty());
        fileRows.removeAll(); lockedRows.removeAll();
        for (var field : session.fileFields()) {
            var label = new TextField();
            label.setValue(field.label()); label.setValueChangeMode(ValueChangeMode.ON_CHANGE);
            label.getElement().setAttribute("data-modify-label", field.name());
            label.setEnabled(why.isEmpty());
            label.addValueChangeListener(e -> { command(() -> { session.setLabel(field.id(), e.getValue()); invalidate(); }); });
            fileRows.add(new Div(new Span(field.name() + " · " + field.type() + " · "), label));
        }
        if (!session.locked().isEmpty()) {
            lockedRows.add(new Span("Из файла, не изменяются:"));
            for (var locked : session.locked()) {
                var row = new Div(new Span(locked.name() + " · " + locked.javaType() + " · из файла: " + reason(locked.reason())));
                row.getElement().setAttribute("data-modify-locked", locked.name());
                lockedRows.add(row);
            }
        }
        renderNew(); invalidate();
    }

    private static String reason(String code) {
        return switch (code) {
            case "NO_FIELD_METADATA" -> "нет @FieldMetadata";
            case "TRANSIENT" -> "не хранится";
            case "ID" -> "идентификатор";
            case "COLLECTION" -> "коллекция";
            case "ENUM" -> "перечисление";
            case "TYPE_NOT_SUPPORTED" -> "тип не поддерживается";
            case "TYPE_UNRESOLVED", "REFERENCE_UNRESOLVED" -> "тип не разрешился";
            case "ASSOCIATION_NOT_SUPPORTED" -> "связь не поддерживается";
            case "MULTIPLE_NAMES" -> "несколько имён в одном объявлении";
            default -> code.startsWith("NOT_LITERAL") ? "значение не литерал (" + code.substring(code.indexOf(':') + 1) + ")" : code;
        };
    }

    private void renderNew() {
        newRows.removeAll();
        for (var field : session.newFields()) newRows.add(newRow(field));
    }

    private Div newRow(FieldDraft field) {
        UUID id = field.id();
        var name = input("name", "Имя", field.name(), v -> edit(id, f -> with(f, v, f.label(), f.type(), f.constraints(), f.reference(), f.applications(), f.columnName())));
        var label = input("label", "Подпись", field.label(), v -> edit(id, f -> with(f, f.name(), v, f.type(), f.constraints(), f.reference(), f.applications(), f.columnName())));
        var kind = new Select<String>(); kind.setItems(TYPES); kind.setValue(field.type()); kind.setLabel("Тип");
        kind.getElement().setAttribute("data-modify-input", "type");
        kind.addValueChangeListener(e -> { { command(() -> {
            session.edit(id, f -> with(f, f.name(), f.label(), e.getValue(), org.ipro.design.RecipeCatalog.initialConstraints(e.getValue()),
                e.getValue().equals("REFERENCE") ? f.reference() : null, f.applications(), f.columnName()));
            invalidate(); renderNew();
        }); } });
        var row = new Div(name, label, kind);
        row.getElement().setAttribute("data-modify-new", field.id().toString());
        if (field.type().equals("STRING")) row.add(input("length", "Длина", field.constraints().length(),
            v -> edit(id, f -> with(f, f.name(), f.label(), f.type(), new ConstraintDraft(v, "", ""), f.reference(), f.applications(), f.columnName()))));
        if (field.type().equals("DECIMAL")) {
            row.add(input("precision", "Точность", field.constraints().precision(),
                v -> edit(id, f -> with(f, f.name(), f.label(), f.type(), new ConstraintDraft("", v, f.constraints().scale()), f.reference(), f.applications(), f.columnName()))));
            row.add(input("scale", "Знаков после запятой", field.constraints().scale(),
                v -> edit(id, f -> with(f, f.name(), f.label(), f.type(), new ConstraintDraft("", f.constraints().precision(), v), f.reference(), f.applications(), f.columnName()))));
        }
        if (field.type().equals("REFERENCE")) row.add(input("reference", "Класс цели", field.reference() == null ? "" : field.reference().typeId(),
            v -> edit(id, f -> with(f, f.name(), f.label(), f.type(), f.constraints(), v.isBlank() ? null : new ReferenceDraft(v), f.applications(), f.columnName()))));
        row.add(flag(id, "form", "Форма", field.applications().form()), flag(id, "list", "Список", field.applications().list()),
            flag(id, "selection", "Выбор", field.applications().selection()));
        var remove = new Button("Удалить", e -> command(() -> { session.removeNew(id); invalidate(); renderNew(); }));
        remove.getElement().setAttribute("data-modify-remove", id.toString());
        row.add(remove);
        return row;
    }

    private TextField input(String key, String caption, String value, java.util.function.Consumer<String> setter) {
        var field = new TextField(caption); field.setValue(value); field.setValueChangeMode(ValueChangeMode.ON_CHANGE);
        field.getElement().setAttribute("data-modify-input", key);
        field.addValueChangeListener(e -> { command(() -> setter.accept(e.getValue())); });
        return field;
    }

    private Checkbox flag(UUID id, String key, String caption, boolean value) {
        var box = new Checkbox(caption, value); box.getElement().setAttribute("data-modify-input", key);
        box.addValueChangeListener(e -> { command(() -> {
            session.edit(id, f -> {
                var a = f.applications();
                var applications = switch (key) {
                    case "form" -> new FieldApplications(e.getValue(), a.list(), a.selection(), a.table());
                    case "list" -> new FieldApplications(a.form(), e.getValue(), a.selection(), a.table());
                    default -> new FieldApplications(a.form(), a.list(), e.getValue(), a.table());
                };
                return with(f, f.name(), f.label(), f.type(), f.constraints(), f.reference(), applications, f.columnName());
            });
            invalidate();
        }); });
        return box;
    }

    private void edit(UUID id, UnaryOperator<FieldDraft> edit) { session.edit(id, edit); invalidate(); }

    private static FieldDraft with(FieldDraft f, String name, String label, String type, ConstraintDraft constraints, ReferenceDraft reference,
                                   FieldApplications applications, DraftText column) {
        return new FieldDraft(f.id(), name, label, type, column, f.serverRequired(), f.uiRequired(), constraints, reference, applications);
    }

    /** Any edit makes the shown plan stale: it is hidden and cannot be applied or downloaded. */
    private void invalidate() {
        plan = null; planRevision = -1;
        apply.setVisible(false); download.setVisible(false); result.removeAll();
        changed.run();
    }

    // ------------------------------------------------------------------ plan

    private void preview() {
        result.removeAll(); plan = null; apply.setVisible(false); download.setVisible(false);
        long revision = session.revision();
        var outcome = session.preview();
        if (outcome.refusal() != null) { showRefusal(outcome.refusal()); return; }
        notice.setText("");
        if (!outcome.problems().isEmpty()) {
            var list = new Div(new Span("Изменение невозможно:"));
            for (var problem : outcome.problems()) list.add(new Div(new Span(problemText(problem))));
            list.getElement().setAttribute("data-modify-problems", "");
            result.add(list);
            return;
        }
        plan = outcome.plan(); planRevision = revision;
        var diff = new Pre(UnifiedDiff.diff(plan.relativePath(), plan.baseContent(), plan.newContent()));
        diff.getElement().setAttribute("data-modify-diff", "");
        var sql = new Pre(plan.schemaStatements().isEmpty() ? "Изменений схемы нет." : String.join("\n", plan.schemaStatements()));
        sql.getElement().setAttribute("data-modify-sql", "");
        result.add(new Span("План " + plan.planId()), diff, new Span("SQL выполняется до запуска обновлённого приложения:"), sql);
        for (var warning : plan.warnings()) result.add(new Div(new Span("Предупреждение: " + problemText(warning))));
        download.setHref(planDownload(plan)); download.setVisible(true);
        apply.setVisible(service.writeAllowed());
        result.add(new Span(service.writeAllowed()
            ? "Файл будет заменён на месте; прежний текст остаётся в base.java плана — скачайте план перед применением."
            : "Запись в исходники выключена (app.entity-draft.allow-source-write). Скачайте план и примените его командой entity-modify apply."));
    }

    private static String problemText(DraftProblem p) {
        if (p.code() == DraftProblem.Code.FORM_EXPLICIT_FIELD_NOT_ADDED) return p.parameters().get("message");
        return p.code() + " · " + p.target().propertyKey() + (p.parameters().isEmpty() ? "" : " · " + p.parameters().values());
    }

    private void showRefusal(SourceRefusal refusal) {
        notice.setText("Отказ " + refusal.code() + " · " + refusal.target() + ": " + refusal.reason());
        notice.getElement().setAttribute("data-modify-refusal", refusal.code().name());
    }

    private void confirmApply() {
        if (plan == null || planRevision != session.revision() || !service.writeAllowed()) { preview(); return; }
        var current = plan;
        var dialog = new ConfirmDialog();
        dialog.setHeader("Применить изменение?");
        dialog.setText("Файл " + current.relativePath() + " будет заменён. Прежний текст сохранён в плане (base.java).");
        dialog.setCancelable(true); dialog.setConfirmText("Применить"); dialog.setCancelText("Отмена");
        dialog.addConfirmListener(e -> command(() -> applyNow(current)));
        add(dialog); dialog.open();
    }

    private void applyNow(ModificationPlan current) {
        if (plan != current || planRevision != session.revision()) { preview(); return; }
        ModificationApply.Result applied;
        try {
            applied = service.apply(session, current);
        } catch (SecurityException refused) {
            activate(); return;
        }
        boolean written = applied.status() == ModificationApply.Status.APPLIED || applied.status() == ModificationApply.Status.NOOP;
        // After a write the file is the new baseline; reading it again redraws the rows and clears the result area.
        if (written) { plan = null; planRevision = -1; apply.setVisible(false); download.setVisible(false); reload(); }
        result.removeAll();
        var message = new Div(new Span(applied.status() + (applied.code().isEmpty() ? "" : " " + applied.code()) + " · " + applied.message()));
        message.getElement().setAttribute("data-modify-status", applied.status().name());
        result.add(message);
        if (written) {
            if (!current.schemaStatements().isEmpty()) result.add(new Span("Выполните SQL из schema.sql плана на базе до запуска обновлённого приложения и пересоберите проект."));
            else result.add(new Span("Пересоберите проект: план проверен только на этом файле."));
        }
        changed.run();
    }

    /** After a write the file is the new baseline. */
    private void reload() {
        try {
            session = service.open(type);
            render();
        } catch (EntityModificationService.ModificationRefused refusal) {
            session = null; showRefusal(refusal.refusal());
        }
    }

    // ------------------------------------------------------------------ plan download

    private DownloadHandler planDownload(ModificationPlan exported) {
        return event -> {
            var failure = new java.util.concurrent.atomic.AtomicReference<IOException>();
            event.getUI().accessSynchronously(() -> {
                if (!permitted() || plan != exported) { event.getResponse().setStatus(409); return; }
                final byte[] bytes;
                try { bytes = zip(exported); }
                catch (IOException failed) { event.getResponse().setStatus(409); return; }
                event.setFileName("entity-modification-" + exported.planId() + ".zip");
                event.setContentType("application/zip");
                event.setContentLength(bytes.length);
                event.getResponse().setHeader("Cache-Control", "no-store");
                try { event.getOutputStream().write(bytes); event.getOutputStream().flush(); }
                catch (IOException failed) { failure.set(failed); }
            });
            if (failure.get() != null) throw failure.get();
        };
    }

    /** The plan directory of {@code entity-modify plan}, zipped, so that the command line can apply it later. */
    static byte[] zip(ModificationPlan plan) throws IOException {
        Path directory = Files.createTempDirectory("entity-modify-plan-");
        try {
            PlanDirectory.write(directory, plan);
            var bytes = new ByteArrayOutputStream();
            try (var out = new ZipOutputStream(bytes); Stream<Path> files = Files.list(directory)) {
                for (Path file : files.sorted().toList()) {
                    out.putNextEntry(new ZipEntry("plan/" + file.getFileName()));
                    out.write(Files.readAllBytes(file)); out.closeEntry();
                }
            }
            return bytes.toByteArray();
        } finally {
            try (Stream<Path> walk = Files.walk(directory)) { walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
    }

    Component resultPanel() { return result; }
}
