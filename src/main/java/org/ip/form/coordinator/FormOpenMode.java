package org.ip.form.coordinator;

/**
 * Режим открытия форм элементов ({@code ItemForm}).
 *
 * @see FormCoordinator#setItemFormOpenMode(FormOpenMode)
 */
public enum FormOpenMode {

    /**
     * Открывать форму в модальном Dialog.
     * Классическое поведение, не блокирует основной список.
     */
    DIALOG,

    /**
     * Открывать форму как новую вкладку в Workspace (1С-стиль).
     * Поддерживает dirty/save-подтверждения при закрытии вкладки.
     */
    WORKSPACE_TAB
}
