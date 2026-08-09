package org.ip.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import org.ip.metadata.annotation.EntityMetadata;
import org.ip.metadata.annotation.FieldMetadata;
import org.ip.metadata.annotation.GridColumn;

/**
 * Сохранённый вид формы списка (аналог "Пользовательских настроек" — конкретно набора
 * колонок — в 1С). Полноценная сущность, а не анонимная запись в UserFormSettings:
 * у вида есть имя, автор (BaseEntity.createdBy — переиспользуем, не заводим отдельное
 * поле), и признак shared.
 *
 * formKey однозначно определяет, к какому ListForm (и, если применимо, варианту формы)
 * относится вид — тот же ключ, что раньше использовался в UserFormSettings
 * ("<EntityClass>[.<variant>]"), см. FormCoordinator.buildFormKey().
 *
 * shared = true — вид общий: виден и редактируем ЛЮБЫМ пользователем.
 * shared = false — личный вид: виден и редактируем только автором
 * (см. GridFormViewService.checkEditable()).
 *
 * "По умолчанию для пользователя" — это НЕ поле на этой сущности, а отдельная запись
 * в UserFormSettings ("listform.defaultview.<formKey>" -> id этого вида) — один вид
 * может быть чьим-то умолчанием, оставаясь обычной записью в общем списке видов.
 *
 * Признак "служебная сущность"/принадлежность к системной подсистеме — сознательно
 * отложено (см. обсуждение), пока сущность просто @EntityMetadata без subsystem().
 */
@Entity
@Table(name = "grid_form_view")
@EntityMetadata(
    listFormTitle = "Виды форм списка",
    itemFormTitle = "Вид формы списка",
    selectionFormTitle = "Выбор вида",
    order = 900,
    icon = "TABLE"
)
public class GridFormView extends BaseEntity {

    @NotBlank
    @Column(name = "form_key", nullable = false)
    @FieldMetadata(
        label = "Реестр (форма)", required = true, order = 1,
        grid = @GridColumn(order = 1, width = "220px")
    )
    private String formKey;

    @NotBlank
    @Column(nullable = false)
    @FieldMetadata(
        label = "Название", required = true, order = 2,
        grid = @GridColumn(order = 2, flexGrow = 1)
    )
    private String name;

    /** Состав колонок в том же ";"-формате, что уже использовался в UserFormSettings. */
    @Column(columnDefinition = "text")
    @FieldMetadata(label = "Колонки", order = 3, grid = @GridColumn(visible = false))
    private String columns;

    @Column(nullable = false)
    @FieldMetadata(
        label = "Общий (виден и редактируем всеми)", order = 4,
        grid = @GridColumn(order = 3, width = "110px")
    )
    private boolean shared;

    public GridFormView() {
    }

    public GridFormView(String formKey, String name, String columns, boolean shared) {
        this.formKey = formKey;
        this.name = name;
        this.columns = columns;
        this.shared = shared;
    }

    public String getFormKey() {
        return formKey;
    }

    public void setFormKey(String formKey) {
        this.formKey = formKey;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getColumns() {
        return columns;
    }

    public void setColumns(String columns) {
        this.columns = columns;
    }

    public boolean isShared() {
        return shared;
    }

    public void setShared(boolean shared) {
        this.shared = shared;
    }

    @Override
    public String toString() {
        return name;
    }
}
