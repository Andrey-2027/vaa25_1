package org.ipro.form.builder;

import org.ipro.form.builtin.ListForm;
import org.ipro.form.registry.FormContext;

/**
 * Поведенческая донастройка собранной Формы Списка без её переписывания.
 *
 * Объявляется внутри конфига сущности ({@link ListFormCustomization} через
 * {@code ListFormVariants.customize...}) — отдельного файла не требует, выполняется
 * после сборки формы (generic или кастомной фабрики) и после параметров открытия.
 * Примеры: {@code form.setReadOnly(true)}, своя кнопка в {@code form.getToolbar()},
 * {@code form.setActiveColumns(...)}.
 *
 * Порядок: сначала default-кастомайзеры сущности (variant = null), затем — вариантные.
 * Никакого нового DSL поверх: обычная процедурная правка готового объекта.
 */
@FunctionalInterface
public interface ListFormCustomizer {

    /**
     * @param form собранная форма (generic или из кастомной фабрики)
     * @param ctx контекст открытия (метаданные, сервисы, бизнес-параметры)
     */
    void customize(ListForm<?, ?> form, FormContext ctx);
}
