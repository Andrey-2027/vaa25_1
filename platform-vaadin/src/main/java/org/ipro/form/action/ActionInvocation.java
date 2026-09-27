package org.ipro.form.action;

import org.ipro.form.coordinator.FormNavigator;
import org.ipro.identity.IdentifiableEntity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Снимок состояния списка на момент исполнения действия (E1.6a).
 *
 * <p>Действие получает <b>снимок</b>, а не форму: выбранную строку, ключ варианта, параметры
 * открытия и значения контекстных фильтров на момент клика, плюс узкий навигационный контракт
 * {@link FormNavigator} и способ обновить список. Так прикладное действие не зависит ни от
 * {@code ListForm}, ни от его приватного состояния, ни от {@code ApplicationContext} — именно это
 * разделяло снятый в E1.7 легаси-{@code ListCommand} и делало его доступность непереносимой
 * в решение.</p>
 *
 * <p>Контекст читается из параметров и фильтров, а не из формы, потому что связь
 * «source → target» прикладной список выражает именно ими. {@code navigator} может быть
 * {@code null} у действия, которому навигация не нужна (локальное действие одного view);
 * {@code refresh} может быть {@code null} вне списка — это состояние явное, а не «пустой»
 * список.</p>
 *
 * @param entityType        тип сущности списка
 * @param variant           ключ варианта списка ({@code null} — default)
 * @param selected          выбранная строка ({@code null}, если действие не требует выделения)
 * @param navigator         навигационный контракт ({@code null} — навигация недоступна)
 * @param refresh           обновление списка после исполнения ({@code null} — не требуется)
 * @param openingParameters параметры открытия списка
 * @param contextFilters    эффективные значения контекстных фильтров
 */
public record ActionInvocation(Class<?> entityType,
                               String variant,
                               IdentifiableEntity selected,
                               FormNavigator navigator,
                               Runnable refresh,
                               Map<String, Object> openingParameters,
                               Map<String, Object> contextFilters) {

    public ActionInvocation {
        Objects.requireNonNull(entityType, "entityType must not be null");
        openingParameters = immutableCopy(openingParameters);
        contextFilters = immutableCopy(contextFilters);
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> values) {
        return values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Выбранная строка, приведённая к типу действия. */
    public <T extends IdentifiableEntity> Optional<T> selectionAs(Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        return type.isInstance(selected) ? Optional.of(type.cast(selected)) : Optional.empty();
    }

    /** Параметр открытия списка ({@code null} — параметра нет). */
    public Object parameter(String name) {
        return openingParameters.get(name);
    }

    /** Значение контекстного фильтра ({@code null} — фильтра нет). */
    public Object contextFilter(String path) {
        return contextFilters.get(path);
    }

    /** Обновление списка: {@code true}, если список его умеет. */
    public boolean canRefresh() {
        return refresh != null;
    }
}
