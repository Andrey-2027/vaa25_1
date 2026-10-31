package org.ipro.report.ui.print;

import jakarta.persistence.Entity;
import org.ipro.form.builder.FormTypeCustomization;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.registry.FormContext;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.report.ui.ReportContextFactory;

import java.util.List;

/**
 * «Отчёты» в подвале любой карточки сущности — общее место подключения, а не вызов
 * {@link ItemFormReportActions} из каждой формы.
 *
 * <p>До этого класса кнопка карточки существовала как шов ({@code ItemFormReportActions}/
 * {@link ContextualReportLauncher}), но её никто не вызывал: печать из списка работала через
 * объявленное действие, а карточка оставалась без входа. Регистрация через
 * {@link FormTypeCustomization} снимает эту асимметрию: платформа сама применяет
 * кастомизацию ко всем формам элементов, приложение не обязано помнить о кнопке в каждом
 * конфиге формы.</p>
 *
 * <p><b>Кому кнопка не нужна.</b> Тип обязан быть JPA-сущностью и
 * {@link IdentifiableEntity}: карточки регистров и служебные формы не получают отчётную кнопку,
 * потому что печать из реестра адресуется сущности по ID. Новая, ещё не сохранённая запись
 * тоже пропускается — печатать нечего, пока нет идентификатора.</p>
 *
 * <p><b>Явное подключение сильнее общего.</b> Если форма уже добавила
 * {@link ContextualReportLauncher} сама, второй не появляется: общий механизм не должен
 * дублировать намеренную настройку конкретной формы.</p>
 *
 * <p>Сборка самой кнопки остаётся в {@link ItemFormReportActions.CardLauncher}: здесь только
 * решение «куда и когда», поэтому отчётные и JR-типы не протекают в общий слой форм.</p>
 */
public class ReportCardPrintCustomization implements FormTypeCustomization {

    private final ItemFormReportActions.CardLauncher launchers;
    private final ReportContextFactory contextFactory;

    public ReportCardPrintCustomization(ItemFormReportActions.CardLauncher launchers,
                                        ReportContextFactory contextFactory) {
        this.launchers = launchers;
        this.contextFactory = contextFactory;
    }

    @Override
    public boolean supports(Class<?> type) {
        return type != null
            && type.isAnnotationPresent(Entity.class)
            && IdentifiableEntity.class.isAssignableFrom(type);
    }

    @Override
    public void customizeItem(ItemForm<?> form, FormContext context) {
        Class<?> entityClass = context.getEntityClass();
        Object id = context.getId();
        if (entityClass == null || id == null) {
            return;
        }
        if (form.getFooter().getChildren().anyMatch(ContextualReportLauncher.class::isInstance)) {
            return;
        }
        launchers.addTo(form, () -> contextFactory.forSelection(entityClass, id, List.of(id),
            entityClass.getName() + "-form"));
    }
}
