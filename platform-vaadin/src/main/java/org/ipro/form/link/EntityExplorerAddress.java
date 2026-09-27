package org.ipro.form.link;

import java.util.Objects;
import java.util.Optional;

/**
 * Адрес типа в Entity Explorer (E3.0): {@code /entity-explorer/{entityKey}}.
 *
 * <p><b>Почему отдельный тип, а не второй кодек.</b> Ключ адреса Explorer — тот же
 * опубликованный ключ, которым адресуется форма ({@link PublishedFormRoute#entityKey()}), и его
 * грамматика берётся у {@link FormRoute#isKey}: второго описания ключа не появляется. Отличие от
 * {@link FormRouteCodec} только в корне адреса — у формы это вид маршрута
 * ({@code records}/{@code lists}), у Explorer раздел {@link #ROOT_SEGMENT}, который не является
 * видом формы и потому не может жить в {@link FormRouteKind}.</p>
 *
 * <p><b>Что отклоняется, а не «исправляется».</b> Так же, как у адреса формы: регистр, лишний
 * сегмент, query-параметр и фрагмент не нормализуются. Терпимость дала бы второй адрес той же
 * карточки, а единственный источник таких украшений — ручная правка ссылки; адрес, отличающийся
 * от канонического, обязан стать отказом. Percent-decoding не выполняется вовсе: канонический
 * ключ — ASCII lower-kebab-case, в кодировании не нуждается, а декодирование лишь открыло бы
 * вторую запись ({@code /entity-explorer/nomenclature} и
 * {@code /entity-explorer/nomencl%61ture}).</p>
 *
 * <p><b>Почему нет причины отказа.</b> {@link #keyOf(String)} отвечает «ключ либо пусто»: причина
 * («нет сегмента», «лишний сегмент», «вне грамматики») наружу не выводится, потому что host
 * показывает один и тот же ответ на любой отказ, включая отказ по роли. Причина, которая может
 * попасть в текст, стала бы источником, различающим состояние ключа.</p>
 *
 * <p><b>Ведущий слэш не обязателен.</b> Оба метода читают первый сегмент одинаково, с ведущим
 * слэшем или без него: адрес окна приходит и без слэша (так его отдаёт {@code Location}), а
 * каноническая форма пишется всегда — {@link #format(String)}. Второй формы адреса из этого не
 * возникает, потому что наружу адрес попадает только через формат.</p>
 *
 * <p><b>Что здесь не решается.</b> Существует ли ключ, чей это тип и не занят ли первый сегмент —
 * вопросы каталога ({@link FormRouteCatalog}) и политики приложения (список {@code reserved} в
 * baseline маршрутов), а не грамматики адреса. Технической коллизии с занятыми первыми сегментами
 * ({@code records}, {@code lists}) у адреса нет: ключ всегда стоит <b>вторым</b> сегментом.
 * Legacy-ключи Explorer тоже не различает — их знает каталог, который по любому опубликованному
 * ключу находит один и тот же {@link PublishedFormRoute}.</p>
 */
public final class EntityExplorerAddress {

    /**
     * Первый сегмент адреса Explorer. Ключ типа всегда стоит <b>вторым</b> сегментом, поэтому
     * технической коллизии с ним у раздела нет; политика приложения лишь не публикует ключ,
     * равный занятому первому сегменту (список {@code reserved} в baseline маршрутов).
     */
    public static final String ROOT_SEGMENT = "entity-explorer";

    private EntityExplorerAddress() {
    }

    /**
     * Ключ типа из адреса Explorer либо пусто, если адрес не является каноническим адресом типа.
     *
     * <p>Пусто — единственный отказ: {@code null}, пустой адрес, корень без ключа, лишний сегмент,
     * query, фрагмент, percent-encoding и ключ вне грамматики отвечают одинаково. Перечисления
     * похожих ключей здесь нет по построению — наружу отдаётся только «есть» или «нет».</p>
     */
    public static Optional<String> keyOf(String address) {
        if (address == null) {
            return Optional.empty();
        }
        String path = withoutLeadingSlash(address.trim());
        int end = path.indexOf('/');
        if (end < 0 || !ROOT_SEGMENT.equals(path.substring(0, end))) {
            return Optional.empty();
        }
        String key = path.substring(end + 1);
        return FormRoute.isKey(key) ? Optional.of(key) : Optional.empty();
    }

    /**
     * Канонический адрес типа. Ключ вне грамматики — ошибка вызова, а не адрес: {@code format}
     * применяется к ключу, полученному из каталога, и «сколько получилось» здесь означало бы
     * ссылку, которую не разберёт {@link #keyOf(String)}.
     */
    public static String format(String entityKey) {
        Objects.requireNonNull(entityKey, "entityKey must not be null");
        if (!FormRoute.isKey(entityKey)) {
            throw new IllegalArgumentException(
                "ключ типа вне грамматики (ASCII lower-kebab-case): '" + entityKey + "'");
        }
        return "/" + ROOT_SEGMENT + "/" + entityKey;
    }

    /**
     * Заявляет ли адрес раздел Explorer — вопрос о разделе, а не о ключе: адрес
     * {@code /entity-explorer} (без ключа) и {@code /entity-explorer/a/b} тоже заявлены, и host
     * отвечает на них тем же отказом, а не показывает главную. Ровно поэтому метод существует
     * отдельно от {@link #keyOf(String)}: «домой» ведёт адрес, который ничего не просит.
     * Query и фрагмент на заявку не влияют: адрес {@code /entity-explorer?tab=data} — всё ещё
     * адрес раздела (ключа у него нет, и он получает тот же отказ, а не главную).
     */
    public static boolean claims(String address) {
        if (address == null) {
            return false;
        }
        String path = withoutLeadingSlash(address.trim());
        int parameters = firstOf(path, '?', '#');
        if (parameters >= 0) {
            path = path.substring(0, parameters);
        }
        int end = path.indexOf('/');
        return (end < 0 ? path : path.substring(0, end)).equals(ROOT_SEGMENT);
    }

    private static int firstOf(String value, char first, char second) {
        int index = value.indexOf(first);
        int other = value.indexOf(second);
        if (index < 0) {
            return other;
        }
        return other < 0 ? index : Math.min(index, other);
    }

    private static String withoutLeadingSlash(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }
}
