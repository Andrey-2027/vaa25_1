package org.ipro.form.link;

import java.util.Objects;
import java.util.Optional;

/**
 * Адрес типа в Entity Explorer (E3.0) и якорь его карточки (E3.2.1):
 * {@code /entity-explorer/{entityKey}}, {@code ?view=<tab>[/<section>]}.
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
 * от канонического, обязан стать отказом. Единственное послабление — инфраструктурный параметр
 * входа {@code ?continue}: тем же правилом, что у {@link FormRouteCodec}, он переносится и в ключ
 * не попадает (см. {@link #keyOf(String)}). Исключение не «послабление», а условие работы
 * ссылки: вход в систему приземляет на запрошенный адрес именно с этим параметром, поэтому
 * отказ на нём означал бы, что скопированная ссылка на тип, открытая до входа, после входа
 * карточку не открывает (проверено на стенде). Percent-decoding не выполняется вовсе:
 * канонический
 * ключ — ASCII lower-kebab-case, в кодировании не нуждается, а декодирование лишь открыло бы
 * вторую запись ({@code /entity-explorer/nomenclature} и
 * {@code /entity-explorer/nomencl%61ture}).</p>
 *
 * <p><b>Якорь — значение {@code ?view}, и его смысл не здесь.</b> Адрес типа может назвать место
 * внутри карточки: вкладку ({@code ?view=access}) или раздел вкладки ({@code ?view=access/rules}).
 * Форму якоря знает платформа, а какое это место — словарь карточки приложения: у платформы
 * вкладок карточки нет, поэтому разбор не выводит из имени якоря смысл и не решает, существует ли
 * место (правило «неизвестный якорь — неизвестный адрес» живёт в host'е, ADR-0010 §10). Отсюда два
 * следствия. Ключ и якорь читаются по отдельности — {@link #keyOf(String)} сохраняет смысл «ключ
 * либо пусто», {@link #anchorOf(String)} отвечает только про якорь; вместе они остаются одной
 * величиной, и адрес с якорем вне грамматики или названным дважды не даёт ни того, ни другого.
 * Второе: имя параметра одно, {@code view}, — {@code ?tab=…} остаётся отказом, иначе у одного
 * места было бы два адреса.</p>
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

    /**
     * Инфраструктурный параметр входа: переносится, ключа не касается. Один и тот же параметр и
     * по той же причине уже переносится адресом формы
     * ({@link FormRouteCodec}: «инфраструктурный параметр Vaadin»); второго описания правила не
     * появляется. Терпимость ограничена именем и пустой строкой не выражается: {@code ?} и
     * {@code ?=x} остаются отказом.
     */
    private static final String CONTINUE_PARAMETER = "continue";

    /**
     * Имя параметра якоря: {@code ?view=<tab>} либо {@code ?view=<tab>/<section>}. Одно на адрес:
     * у одного места карточки не должно быть двух написаний, поэтому повтор параметра и любое
     * второе имя ({@code ?tab=…}) — отказ.
     */
    private static final String VIEW_PARAMETER = "view";

    private EntityExplorerAddress() {
    }

    /**
     * Ключ типа из адреса Explorer либо пусто, если адрес не является каноническим адресом типа.
     *
     * <p>Пусто — единственный отказ: {@code null}, пустой адрес, корень без ключа, лишний сегмент,
     * неизвестное имя параметра, якорь вне грамматики или названный дважды, фрагмент,
     * percent-encoding и ключ вне грамматики отвечают одинаково. Перечисления похожих ключей здесь
     * нет по построению — наружу отдаётся только «есть» или «нет».</p>
     *
     * <p>Существует ли названный якорь, метод не решает: словарь карточки — приложение, и
     * {@link #anchorOf(String)} отвечает про форму якоря, а не про место.</p>
     */
    public static Optional<String> keyOf(String address) {
        return read(address).map(Address::key);
    }

    /**
     * Якорь карточки из адреса типа либо пусто, если адрес якоря не называет. У разобранного адреса
     * пусто означает «якоря нет»: отказ адреса виден по {@link #keyOf(String)} — он пуст в обоих
     * случаях, и второго отказа здесь не заводится.
     *
     * <p>Что якорь называет — вкладку ({@code access}) или раздел вкладки ({@code access/rules}) —
     * решает словарь приложения: платформа знает только форму, поэтому «неизвестный якорь» и
     * «неизвестный ключ» дают один отказ host'а (ADR-0010 §10).</p>
     */
    public static Optional<String> anchorOf(String address) {
        return read(address).map(Address::anchor).filter(Objects::nonNull);
    }

    /** Разобранный адрес типа: ключ и, если якорь назван, якорь; {@code null} — якоря нет. */
    private record Address(String key, String anchor) {
    }

    /**
     * Читает адрес один раз для обоих публичных разборов: грамматика адреса одна, и её второй
     * экземпляр разошёлся бы с первым при первом же изменении.
     */
    private static Optional<Address> read(String address) {
        if (address == null) {
            return Optional.empty();
        }
        String path = withoutLeadingSlash(address.trim());
        int end = path.indexOf('/');
        if (end < 0 || !ROOT_SEGMENT.equals(path.substring(0, end))) {
            return Optional.empty();
        }
        String key = path.substring(end + 1);
        String anchor = null;
        int question = key.indexOf('?');
        if (question >= 0) {
            String query = key.substring(question + 1);
            key = key.substring(0, question);
            for (String parameter : query.split("&", -1)) {
                int equals = parameter.indexOf('=');
                String name = equals < 0 ? parameter : parameter.substring(0, equals);
                if (CONTINUE_PARAMETER.equals(name)) {
                    continue;
                }
                if (!VIEW_PARAMETER.equals(name) || equals < 0 || anchor != null) {
                    return Optional.empty();
                }
                String value = parameter.substring(equals + 1);
                if (!isAnchor(value)) {
                    return Optional.empty();
                }
                anchor = value;
            }
        }
        return FormRoute.isKey(key) ? Optional.of(new Address(key, anchor)) : Optional.empty();
    }

    /**
     * Якорь: {@code <id>} либо {@code <id>/<id>}, где каждое {@code id} — грамматика ключа.
     * Второго описания идентификатора не появляется; смысл частей (вкладка, раздел) адресу
     * неизвестен так же, как и существование названного места.
     */
    private static boolean isAnchor(String value) {
        int slash = value.indexOf('/');
        if (slash < 0) {
            return FormRoute.isKey(value);
        }
        return FormRoute.isKey(value.substring(0, slash))
            && FormRoute.isKey(value.substring(slash + 1));
    }

    /**
     * Канонический адрес типа без якоря: карточка открывается как сегодня — первым непустым
     * аспектом.
     */
    public static String format(String entityKey) {
        return format(entityKey, null);
    }

    /**
     * Канонический адрес типа с якорем: {@code ?view=<tab>} либо {@code ?view=<tab>/<section>}.
     * {@code null} здесь не пустое значение, а отсутствие якоря: пустой якорь грамматикой
     * отвергается, поэтому «якорь пуст» не существует. Ключ и якорь вне грамматики — ошибка
     * вызова, а не адрес: {@code format} применяется к тому, что дал словарь каталога и карточки,
     * и «сколько получилось» здесь означало бы ссылку, которую не разберёт {@link #keyOf(String)}.
     */
    public static String format(String entityKey, String anchor) {
        Objects.requireNonNull(entityKey, "entityKey must not be null");
        if (!FormRoute.isKey(entityKey)) {
            throw new IllegalArgumentException(
                "ключ типа вне грамматики (ASCII lower-kebab-case): '" + entityKey + "'");
        }
        if (anchor == null) {
            return "/" + ROOT_SEGMENT + "/" + entityKey;
        }
        if (!isAnchor(anchor)) {
            throw new IllegalArgumentException(
                "якорь вне грамматики (<tab> либо <tab>/<section>): '" + anchor + "'");
        }
        return "/" + ROOT_SEGMENT + "/" + entityKey + "?" + VIEW_PARAMETER + "=" + anchor;
    }

    /**
     * Заявляет ли адрес раздел Explorer — вопрос о разделе, а не о ключе: адрес
     * {@code /entity-explorer} (без ключа) и {@code /entity-explorer/a/b} тоже заявлены, и host
     * отвечает на них тем же отказом, а не показывает главную. Ровно поэтому метод существует
     * отдельно от {@link #keyOf(String)}: «домой» ведёт адрес, который ничего не просит.
     * Query и фрагмент на заявку не влияют: адрес {@code /entity-explorer?view=access} — всё ещё
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
