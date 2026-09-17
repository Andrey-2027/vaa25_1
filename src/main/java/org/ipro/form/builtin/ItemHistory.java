package org.ipro.form.builtin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.form.FieldRenderer;
import org.ipro.metadata.annotation.TableSectionMetadata;
import org.ipro.telemetry.core.FieldAuditBridge;
import org.ipro.telemetry.core.FieldAuditQueryService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * История изменений записи (этап 10): кнопка «История», журнал entity_change_log,
 * drill-down «поле | было | стало». Вынесено из {@link ItemForm} без изменений поведения.
 *
 * Владеет кнопкой целиком (создаёт, кладёт в footer через колбэк, переключает видимость).
 * Обновляется вызовом {@link #update()} из путей установки сущности формы.
 */
final class ItemHistory<T extends IdentifiableEntity> {

    private final Button historyButton = new Button("История", VaadinIcon.CLOCK.create());
    private final Class<T> entityClass;
    private final Supplier<T> currentEntity;
    private final Supplier<T> peekEntity;

    /**
     * @param entityClass класс сущности формы (для имени в журнале; строкам — имя родителя)
     * @param currentEntity текущая сущность без побочных эффектов (может быть null)
     * @param peekEntity сущность с ленивым созданием (как {@code ItemForm.peekEntity()})
     * @param addFooterFirst куда положить кнопку (начало footer формы)
     */
    ItemHistory(Class<T> entityClass, Supplier<T> currentEntity, Supplier<T> peekEntity,
                Consumer<Component> addFooterFirst) {
        this.entityClass = entityClass;
        this.currentEntity = currentEntity;
        this.peekEntity = peekEntity;
        initHistoryButton();
        addFooterFirst.accept(historyButton);
    }

    /**
     * Кнопка «История» в footer: видна только ADMIN и только для существующей
     * записи (entity с id). Открывает журнал entity_change_log, отфильтрованный
     * по текущей записи; drill-down — диалог «поле | было | стало».
     * Для форм строк табличных частей история агрегируется на родителе
     * (@TableSectionMetadata.parentEntity/parentField).
     */
    private void initHistoryButton() {
        historyButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
        historyButton.setVisible(false);
        historyButton.addClickListener(e -> openHistoryDialog());
    }

    void update() {
        T entity = currentEntity.get();
        historyButton.setVisible(isAdmin() && entity != null && entity.getId() != null);
    }

    private static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()));
    }

    private void openHistoryDialog() {
        FieldAuditQueryService query = FieldAuditBridge.queryService();
        if (query == null) {
            Notification.show("Телеметрия выключена (ipro.telemetry.enabled=false)");
            return;
        }
        T current = peekEntity.get();
        if (current == null || current.getId() == null) {
            return;
        }
        String entityName = historyEntityName();
        String entityId = historyEntityId(current);

        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("История изменений: " + entityName + " #" + entityId);
        dialog.setWidth("900px");
        dialog.setHeight("560px");

        Grid<FieldAuditQueryService.ChangeRow> grid =
                new Grid<>(FieldAuditQueryService.ChangeRow.class, false);
        grid.addColumn(r -> FieldRenderer.dateTimeWithSeconds().apply(
                r.changedAt() == null ? null : r.changedAt().atZone(ZoneId.systemDefault())))
                .setHeader("Время");
        grid.addColumn(FieldAuditQueryService.ChangeRow::changeType).setHeader("Тип");
        grid.addColumn(FieldAuditQueryService.ChangeRow::userId).setHeader("Пользователь");
        grid.addColumn(FieldAuditQueryService.ChangeRow::fieldCount).setHeader("Полей");
        grid.addItemDoubleClickListener(e -> openChangeDetail(query, e.getItem().id()));
        try {
            grid.setItems(query.queryChanges(new FieldAuditQueryService.ChangeFilter(
                    entityName, entityId, null, null, null, 200)));
        } catch (RuntimeException e) {
            // Vaadin UI-поток может не иметь SecurityContext (ROLE_ADMIN в query.
            // ShowErrorMessage: вместо пустого грида - понятное сообщение.
            Notification.show("Нет доступа к журналу: " + e.getMessage());
        }

        dialog.add(grid);
        dialog.open();
    }

    private void openChangeDetail(FieldAuditQueryService query, long changeId) {
        String payload = query.payloadById(changeId);
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Изменение полей (id=" + changeId + ")");
        dialog.setWidth("750px");
        dialog.setHeight("480px");
        if (payload == null || payload.isBlank()) {
            dialog.add(new com.vaadin.flow.component.html.Span("payload отсутствует"));
        } else {
            Grid<FieldDiff> grid = new Grid<>(FieldDiff.class, false);
            grid.addColumn(FieldDiff::field).setHeader("Поле").setAutoWidth(true);
            grid.addColumn(FieldDiff::oldValue).setHeader("Было").setWidth("280px");
            grid.addColumn(FieldDiff::newValue).setHeader("Стало").setWidth("280px");
            grid.setItems(parseDiff(payload));
            dialog.add(grid);
        }
        dialog.open();
    }

    /** Имя сущности для журнала: для строк табличных частей — имя родителя. */
    private String historyEntityName() {
        TableSectionMetadata section = entityClass.getAnnotation(TableSectionMetadata.class);
        return section != null ? section.parentEntity().getSimpleName() : entityClass.getSimpleName();
    }

    /** ID записи для журнала: для строк табличных частей — id родителя. */
    private String historyEntityId(T current) {
        TableSectionMetadata section = entityClass.getAnnotation(TableSectionMetadata.class);
        if (section == null) {
            return String.valueOf(current.getId());
        }
        try {
            String getter = "get" + Character.toUpperCase(section.parentField().charAt(0))
                    + section.parentField().substring(1);
            Method getParent = entityClass.getMethod(getter);
            Object parent = getParent.invoke(current);
            if (parent != null) {
                Object parentId = parent.getClass().getMethod("getId").invoke(parent);
                if (parentId != null) {
                    return String.valueOf(parentId);
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // падение резолва родителя — показываем id строки
        }
        return String.valueOf(current.getId());
    }

    private record FieldDiff(String field, String oldValue, String newValue) {
    }

    private static List<FieldDiff> parseDiff(String payload) {
        try {
            JsonNode root = new ObjectMapper().readTree(payload);
            List<FieldDiff> result = new ArrayList<>();
            if (root.isArray()) {
                for (JsonNode node : root) {
                    String field = node.get("field") == null ? "" : node.get("field").asText();
                    if (node.has("added") || node.has("removed") || node.has("changed")) {
                        String summary = "добавлено: " + num(node, "added")
                                + ", удалено: " + num(node, "removed")
                                + ", изменено: " + num(node, "changed");
                        result.add(new FieldDiff(field, "", summary));
                    } else {
                        result.add(new FieldDiff(field, text(node.get("old")), text(node.get("new"))));
                    }
                }
            }
            return result;
        } catch (Exception e) {
            return List.of(new FieldDiff("payload", e.toString(), ""));
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? "" : node.asText();
    }

    private static String num(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null ? "0" : String.valueOf(value.asInt());
    }
}
