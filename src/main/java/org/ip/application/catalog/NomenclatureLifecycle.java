package org.ip.application.catalog;

import org.ip.model.NomAttributeValue;
import org.ip.model.Nomenclature;
import org.ipro.crud.ValidationException;
import org.ipro.lifecycle.AggregateSaveContext;
import org.ipro.lifecycle.EntityLifecycle;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lifecycle-правила агрегата {@link Nomenclature}.
 *
 * <p>{@link Nomenclature} — aggregate root, {@link NomAttributeValue} — owned
 * {@code MUTABLE_REPLACE_ALL} section (объявлена через {@code @TableSections}).
 * Шапка и строки сохраняются одной транзакцией metadata-driven aggregate save,
 * поэтому предметные правила «Атрибут — значение» проверяются здесь, вместе с
 * фактически подключённой секцией:
 *
 * <ul>
 *   <li>{@code ABSENT} — секция не подключена, существующие значения не изменяются;</li>
 *   <li>{@code ATTACHED + empty} — значения позиции очищаются (replace-all);</li>
 *   <li>{@code ATTACHED + rows} — ввод формы разрешается в строки словаря и заменяет набор.</li>
 * </ul>
 *
 * <p>Межстрочные правила (один атрибут — не более одной строки, активность типа)
 * принадлежат агрегату, а не отдельной строке, поэтому живут здесь, а не в сервисе строки.
 */
@Component
public class NomenclatureLifecycle implements EntityLifecycle<Nomenclature> {

    private final ObjectProvider<NomAttributeValueValueRule> valueRuleProvider;

    public NomenclatureLifecycle(ObjectProvider<NomAttributeValueValueRule> valueRuleProvider) {
        this.valueRuleProvider = Objects.requireNonNull(
            valueRuleProvider, "valueRuleProvider must not be null");
    }

    @Override
    public Class<Nomenclature> entityType() {
        return Nomenclature.class;
    }

    @Override
    public void beforeAggregateSave(AggregateSaveContext<Nomenclature> context) {
        List<NomAttributeValue> rows = context.section(NomAttributeValue.class);
        if (rows.isEmpty()) {
            // Секция не подключена либо осознанно очищена: разбирать нечего.
            return;
        }

        NomAttributeValueValueRule valueRule = valueRuleProvider.getObject();
        List<String> errors = new ArrayList<>();
        Map<Long, Integer> firstRowByType = new LinkedHashMap<>();
        int lineNumber = 0;
        for (NomAttributeValue row : rows) {
            lineNumber++;
            if (row == null) {
                errors.add("Строка " + lineNumber + ": пустая строка атрибута");
                continue;
            }
            try {
                valueRule.resolve(row);
            } catch (ValidationException invalid) {
                errors.add("Строка " + lineNumber + ": " + invalid.getMessage());
                continue;
            }
            Integer firstLine = firstRowByType.putIfAbsent(
                row.getAttrType().getId(), lineNumber);
            if (firstLine != null) {
                errors.add("Строка " + lineNumber + ": атрибут «"
                    + row.getAttrType().getDisplayName() + "» уже указан в строке " + firstLine);
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException(String.join(System.lineSeparator(), errors));
        }
    }
}
