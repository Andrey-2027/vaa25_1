package org.ipro.form.builder;

import org.ipro.form.builtin.ItemForm;
import org.ipro.form.registry.FormContext;

/**
 * Поведенческая донастройка собранной Формы Элемента без её переписывания.
 *
 * Объявляется внутри конфига сущности ({@link ItemFormCustomization} через
 * {@code ItemFormVariants.customize...}) — отдельного файла не требует, выполняется
 * после сборки формы (generic или кастомной фабрики), но ДО подключения табличных
 * частей ({@code TableSectionFactory}): структурные изменения набора полей —
 * по-прежнему делом фабрики, кастомайзер — про поведение
 * ({@code setReadOnly}, {@code setSectionFilter}, {@code setRlsReadOnlyNotice},
 * свои кнопки в {@code getFooter()}).
 *
 * Порядок: сначала default-кастомайзеры сущности (variant = null), затем — вариантные.
 * Никакого нового DSL поверх: обычная процедурная правка готового объекта.
 */
@FunctionalInterface
public interface ItemFormCustomizer {

    /**
     * @param form собранная форма (generic или из кастомной фабрики)
     * @param ctx контекст открытия (метаданные, фабрика полей, бизнес-параметры)
     */
    void customize(ItemForm<?> form, FormContext ctx);
}
