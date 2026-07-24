package org.ip.form.coordinator;

/**
 * Режим открытия формы в сессии.
 */
public enum SessionMode {
    /**
     * Форма списка (ListForm) — отображает список записей.
     */
    LIST,

    /**
     * Форма элемента (ItemForm) — создание/редактирование одной записи.
     */
    ITEM,

    /**
     * Форма выбора (SelectionForm) — выбор связанной записи для EntityField.
     */
    SELECTION
}
