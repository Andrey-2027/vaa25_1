package org.ip.views.admin;

import java.util.Optional;

/**
 * Якорь адреса карточки типа (E3.2.1 §8.1): вкладка карточки и — если якорь её называет — раздел
 * внутри вкладки. Единственное место, знающее имя якоря: платформенная грамматика адреса
 * ({@code EntityExplorerAddress#anchorOf}) отвечает только за форму, а «есть ли такое место»
 * решает словарь карточки — он же рисует её вкладки.
 *
 * <p>Неизвестный якорь — это неизвестный адрес (ADR-0010 §10): host отвечает тем же единым
 * отказом, что и на неизвестный ключ типа, и не открывает «ближайшую» вкладку. Поэтому у разбора
 * ровно два исхода — место есть или места нет, — а «почти угадали» не выражается.</p>
 *
 * <p>Раздел без вкладки якорь назвать не может: раздел живёт внутри вкладки, поэтому якорь
 * {@code access/rules} несёт и её имя. Вкладка без раздела — обычный якорь ({@code access}):
 * панель открывает первую непустую секцию вкладки, как если бы вкладку выбрал человек.</p>
 *
 * <p><b>Почему это публично, а состав карточки — нет.</b> Решение про якорь принимает host
 * (пакет {@code org.ip.views}) <b>до</b> открытия вида — неизвестный якорь обязан получить отказ,
 * а не карточку без места (E3.2.1 §8.2), — поэтому сам словарь якоря виден host'у. Вкладки и
 * разделы ({@link CardTab}, {@link CardSection}) остаются package-private: наружу их не выносит
 * ни один вызов, а снаружи пакета видны только «место есть» и каноническое значение якоря.</p>
 */
public record CardAnchor(CardTab tab, CardSection<?> section) {

    /** Якорь вкладки без раздела: место «вкладка целиком». */
    static CardAnchor of(CardTab tab) {
        return new CardAnchor(tab, null);
    }

    /**
     * Место карточки, которое называет якорь адреса; пусто — словарь такого места не знает. Форму
     * якоря здесь не перепроверяют: она уже проверена грамматикой адреса, и вторая её проверка
     * завела бы второе описание грамматики.
     */
    public static Optional<CardAnchor> of(String anchor) {
        if (anchor == null || anchor.isBlank()) {
            return Optional.empty();
        }
        String[] parts = anchor.split("/", -1);
        if (parts.length == 1) {
            return CardTab.byId(parts[0]).map(tab -> new CardAnchor(tab, null));
        }
        if (parts.length == 2) {
            return CardTab.byId(parts[0]).flatMap(tab -> CardSection.byId(tab, parts[1])
                .map(section -> new CardAnchor(tab, section)));
        }
        return Optional.empty();
    }

    /** Каноническое значение якоря этого места — то же, что несёт адрес: {@code <tab>[/<section>]}. */
    public String value() {
        return section == null ? tab.id() : tab.id() + "/" + section.id();
    }

    /**
     * Место панели для этого якоря. Третий признак {@link CardSection.Location} — «место
     * адресовано»: якорь назвал его так же, как ключ владельца называет место диагностики.
     */
    CardSection.Location location() {
        return new CardSection.Location(tab, section, true);
    }
}
