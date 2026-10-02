package org.ip.views.admin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Последний выбор одного UI-scoped Explorer (E3.2.2 §4.4): тип карточки и её место. Состояние
 * живёт у самого вида и не хранится ни в сущности, ни в БД, ни в транспортном snapshot'е.
 *
 * <p><b>Зачем отдельный владелец.</b> Host восстанавливает выбор из меню и не заводит второй кеш
 * «последний тип»: результат {@link #restore(Predicate)} — единственный источник и для карточки, и
 * для адреса вкладки. Новый UI начинает с пустым состоянием; при смене пользователя, ролей или
 * локали вид явно очищает состояние при следующем входе.</p>
 *
 * <p><b>Сохранённый выбор проверяется на допустимость.</b> Если тип исчез из текущего снимка
 * (явное обновление метаданных), выбор снимается, {@link #lastRestoreFailed()} отмечает причину, а
 * старый адрес не восстанавливается: восстановление не выдаёт недоступное за доступное.</p>
 */
final class ExplorerUiState {

    /** Восстановленный выбор: тип и место карточки; адрес собирает host — вид его не знает. */
    record Selection(Class<?> type, String anchor) {
    }

    private Class<?> selectedType;
    private String anchor;
    private boolean restoreFailed;
    private final Map<Class<?>, String> places = new LinkedHashMap<>();
    private final Map<String, Boolean> expansions = new LinkedHashMap<>();

    /** Пользователь или программный вход выбрал тип и место: {@code anchor} пуст — место не названо. */
    void remember(Class<?> type, String anchor) {
        this.selectedType = type;
        this.anchor = anchor;
        this.restoreFailed = false;
        places.put(type, anchor);
    }

    /** Пользователь выбрал место уже открытой карточки (вкладку или раздел). */
    void rememberPlace(String anchor) {
        if (selectedType != null) {
            this.anchor = anchor;
            this.restoreFailed = false;
            places.put(selectedType, anchor);
        }
    }

    String placeOf(Class<?> type) {
        return places.get(type);
    }

    void rememberExpansions(Map<String, Boolean> visible) {
        expansions.putAll(visible);
    }

    Map<String, Boolean> expansions() {
        return Map.copyOf(expansions);
    }

    void clearExpansions() {
        expansions.clear();
    }

    boolean hasSelection() {
        return selectedType != null;
    }

    /**
     * Восстановление для входа из меню. Недоступный тип снимает выбор и называет причину; отказ
     * ничего не подменяет и не изображает сохранённое место чужим типом.
     */
    Optional<Selection> restore(Predicate<Class<?>> available) {
        restoreFailed = false;
        if (selectedType == null) {
            return Optional.empty();
        }
        if (!available.test(selectedType)) {
            clear();
            restoreFailed = true;
            return Optional.empty();
        }
        return Optional.of(new Selection(selectedType, anchor));
    }

    /** Последнее восстановление сняло выбор: администратору показывается причина. */
    boolean lastRestoreFailed() {
        return restoreFailed;
    }

    /** Сброс выбора: отказ по роли/адресу сохранённый выбор не меняет — вызывается явно. */
    void clear() {
        selectedType = null;
        anchor = null;
        restoreFailed = false;
        places.clear();
        expansions.clear();
    }
}
