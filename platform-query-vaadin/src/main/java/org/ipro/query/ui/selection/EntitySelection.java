package org.ipro.query.ui.selection;

import com.vaadin.flow.component.Component;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.HasDisplayName;

import java.util.List;
import java.util.function.Consumer;

/**
 * M1.2 seam: «выбрать ссылочную сущность» без знания о форме платформы.
 *
 * <p>Раньше редактор запроса импортировал {@code org.ipro.form.EntityField} и
 * {@code SelectionFormAssembler} напрямую. Это держало query UI в дереве приложения:
 * артефакт не может зависеть от UI-платформы приложения, не потащив за собой её
 * формы, lookup и Spring-граф. Здесь остаётся только то, что редактору действительно
 * нужно: колонки выбора и поле, у которого читается/пишется выбранная сущность.
 * Реализацию даёт потребитель — в приложении это адаптер над формой платформы.
 *
 * <p>Порт намеренно не называет {@code SelectionForm}: модальный диалог выбора —
 * деталь реализации поля. Редактору важен только результат.
 */
public interface EntitySelection {

    /**
     * Колонки формы выбора сущности в порядке показа.
     *
     * @throws RuntimeException если у сущности нет конфигурации выбора; вызывающая
     *                          сторона показывает это как подсказку, а не падение
     */
    List<ColumnPath> columns(Class<?> entityClass);

    /** Поле выбора сущности; поиск и модальный выбор собирает реализация. */
    Field field(String label, Class<?> entityClass);

    /** Поле выбора: компонент для раскладки плюс типизированное значение. */
    interface Field {

        Component component();

        HasDisplayName value();

        void setValue(HasDisplayName value);

        void addValueChangeListener(Consumer<HasDisplayName> listener);
    }
}
