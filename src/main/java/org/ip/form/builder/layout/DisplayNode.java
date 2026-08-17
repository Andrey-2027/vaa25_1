package org.ip.form.builder.layout;

import java.util.function.Function;

/**
 * Read-only поле-вывод: значение вычисляется из сущности через {@code formatter}
 * и обновляется при каждом {@code setEntity()} (через displayRefreshers).
 *
 * Не регистрируется в FormBindingRegistry — dirty/required-валидации и сохранения нет
 * (спецификация «Часть D.5», PR-1.2). Для редактируемых полей используйте
 * {@code FieldNode}/{@code bindExternal(...)}, НЕ DisplayNode.
 *
 * @param key       ключ (имя) поля — для отладки/тестов
 * @param label     подпись («заголовок слева, поле справа» через FormLayout.addFormItem)
 * @param formatter форматтер значения; получает сущность (всегда не-null в setEntity)
 */
public record DisplayNode(String key, String label, Function<Object, String> formatter)
        implements LayoutNode {
}
