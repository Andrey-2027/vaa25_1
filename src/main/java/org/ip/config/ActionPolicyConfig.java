package org.ip.config;

import org.ip.model.AttributeValue;
import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.action.CrudAction;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Прикладная UI-policy стандартных действий (E1.4, §2.3 плана E1) — объявления на уровне
 * приложения, а не платформы.
 *
 * <p>Платформа выводит состав кнопок из capability типа, и для большинства сущностей этого
 * достаточно: тип без {@code UPDATE} не получит «Изменить», тип без {@code CREATE} — «Создать».
 * Здесь описан случай, который из capability не выводится: у {@link AttributeValue} generic
 * {@code CREATE} <b>разрешён</b> (значения создаются интернированием,
 * {@code EntityClassificationConfig#attributeValueIsCreateOnly}), но generic создание — не тот путь,
 * которым значение должно появляться. Рабочий путь — «Добавить значение» в карточке типа атрибута
 * ({@code AttributeTypeForm} → {@code AttributeValueService.createEnumValue} /
 * {@code getOrCreate}), где значение создаётся в контексте типа и дедуплицируется канонически.</p>
 *
 * <p>Поэтому generic {@code Create} и {@code Copy} для этого типа подавляются на поверхности списка
 * (suppress-override, §2.4). Это <b>только</b> решение UI: capability типа не сужается, backend
 * {@code CREATE} остаётся разрешённым (§2.3 требует не трогать его в E1), серверные границы и
 * уникальный индекс {@code (attr_type_id, code_up)} не меняются. Подавление адресовано типу, а не
 * действию вообще, поэтому соседние сущности сохраняют свои «Создать»/«Копировать».</p>
 *
 * <p>У {@code SklNomOpa} объявлений здесь нет намеренно: у него нет generic-записей вообще, поэтому
 * обещанный §2.3 состав списка ({@code Open} и {@code Refresh}) получается из capability, а не из
 * ручного скрытия кнопок. Это проверяется в {@code ActionPolicyConfigTest}.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ActionPolicyConfig {

    /**
     * Значение атрибута не создаётся generic-действием: правильный путь — «Добавить значение» в
     * карточке типа атрибута, где значение попадает в интернирование.
     */
    @Bean
    public ActionDefinition attributeValueIsNotCreatedByGenericAction() {
        return ActionDefinition.suppress(CrudAction.CREATE, ActionSurface.LIST_TOOLBAR,
            AttributeValue.class);
    }

    /**
     * Копирование значения атрибута тоже подавлено: копия — это новая строка словаря со своим
     * ключом интернирования, то есть создание в обход того же предметного пути (§2.3: интернированные
     * типы не выдают generic {@code Copy}).
     */
    @Bean
    public ActionDefinition attributeValueIsNotCopiedByGenericAction() {
        return ActionDefinition.suppress(CrudAction.COPY, ActionSurface.LIST_TOOLBAR,
            AttributeValue.class);
    }
}
