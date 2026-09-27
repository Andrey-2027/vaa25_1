package org.ipro.form.link;

/**
 * Единственное место, знающее синтаксис адреса глубокой ссылки (E2.1, ADR-0009 §3):
 * {@link #format(FormRoute)} даёт каноническую форму, {@link #parse(String)} — разбор
 * с причинами отказа.
 *
 * <p><b>Что считается канонической формой.</b> Ровно:</p>
 * <pre>
 * /records/{entityKey}/{id}      — карточка существующей записи
 * /lists/{entityKey}             — список
 * ?variant={variantKey}          — именованный вариант; у default-варианта параметра нет
 * </pre>
 *
 * <p><b>Что отклоняется, а не «исправляется».</b> Ошибка адреса должна приводить к 404, а не
 * к открытию другой формы, поэтому кодек не нормализует регистр, не срезает ведущие нули
 * идентификатора, не выбирает один из двух {@code variant} и не игнорирует неизвестный
 * query-параметр: адрес, отличающийся от канонического, кем-то сгенерирован вручную или
 * по ошибке. Единственное исключение — {@code continue}: его добавляет Vaadin при redirect
 * на login (измерено в E2.0), он переносится как есть и в форму не попадает.</p>
 *
 * <p>Отличия от «разумной терпимости» перечислены, потому что каждое из них — решение:</p>
 * <ul>
 * <li>{@code /records/Nomenclature/42} — регистр ключа не нормализуется: адрес вне грамматики;</li>
 * <li>{@code /records/nomenclature/042} — ведущий ноль отклоняется: {@code 42} и {@code 042}
 *     не должны быть двумя адресами одной записи;</li>
 * <li>{@code /records/nomenclature/42?variant=} — пустой вариант не равен отсутствию варианта;</li>
 * <li>{@code ?variant=a&variant=b} — повтор параметра отклоняется, а не решается по последнему;</li>
 * <li>{@code ?foo=1} — неизвестный параметр отклоняется: иначе опечатка ({@code varaint}) молча
 *     открывала бы default-форму вместо запрошенной;</li>
 * <li>абсолютный адрес ({@code https://.../records/...}) отклоняется: с origin работает только
 *     UI-слой, а маршрут остаётся относительным — иначе baseline и тесты зависят от стенда;</li>
 * <li>фрагмент ({@code #...}) отбрасывается: он не является частью контракта и не влияет на
 *     форму.</li>
 * </ul>
 *
 * <p>Percent-encoding разбирается один раз для ключа, идентификатора и варианта: после
 * декодирования значение обязано попадать в грамматику. {@code +} не трактуется как пробел —
 * это path-сегмент и query-значение, а не форма.</p>
 */
public class FormRouteCodec {

    /** Инфраструктурный параметр Vaadin (redirect на login): переносится, в форму не попадает. */
    private static final String CONTINUE_PARAMETER = "continue";

    private static final String VARIANT_PARAMETER = "variant";

    /** Каноническая форма адреса: единственный источник истины для генерации ссылок. */
    public String format(FormRoute route) {
        StringBuilder path = new StringBuilder(48);
        path.append('/').append(route.kind().segment()).append('/').append(route.entityKey());
        if (route.kind() == FormRouteKind.ITEM) {
            path.append('/').append(route.id());
        }
        if (route.variant() != null) {
            path.append('?').append(VARIANT_PARAMETER).append('=').append(route.variant());
        }
        return path.toString();
    }

    /** Разбор адреса. Путь относительный: {@code /records/...} либо {@code /lists/...}. */
    public FormRouteParseResult parse(String url) {
        if (url == null || url.isBlank()) {
            return FormRouteParseResult.rejected("пустой адрес");
        }
        String value = url.trim();
        if (value.indexOf("://") >= 0 || !value.startsWith("/")) {
            return FormRouteParseResult.rejected(
                "маршрут должен быть относительным путём, начинающимся с '/': '" + url + "'");
        }
        int fragment = value.indexOf('#');
        if (fragment >= 0) {
            value = value.substring(0, fragment);
        }

        String query = null;
        int question = value.indexOf('?');
        if (question >= 0) {
            query = value.substring(question + 1);
            value = value.substring(0, question);
        }

        VariantParse variantParse = variantOf(query);
        if (variantParse.rejection() != null) {
            return FormRouteParseResult.rejected(variantParse.rejection());
        }
        String variant = variantParse.variant();

        String[] segments = value.split("/", -1);
        if (segments[0].isEmpty() && segments.length >= 2) {
            String kindSegment = segments[1];
            FormRouteKind kind = FormRouteKind.ofSegment(kindSegment).orElse(null);
            if (kind == null) {
                return FormRouteParseResult.rejected("неизвестный раздел адреса: '" + kindSegment + "'");
            }
            if (kind == FormRouteKind.ITEM) {
                if (segments.length != 4) {
                    return FormRouteParseResult.rejected(
                        "карточка требует ровно /records/{entityKey}/{id}");
                }
                String entityKey = decode(segments[2]);
                if (!FormRoute.isKey(entityKey)) {
                    return FormRouteParseResult.rejected("entityKey вне грамматики: '" + segments[2] + "'");
                }
                Long id = parseId(segments[3]);
                if (id == null) {
                    return FormRouteParseResult.rejected("id вне грамматики: '" + segments[3] + "'");
                }
                return FormRouteParseResult.parsed(
                    new FormRoute(FormRouteKind.ITEM, entityKey, id, variant));
            }
            if (segments.length != 3) {
                return FormRouteParseResult.rejected("список требует ровно /lists/{entityKey}");
            }
            String entityKey = decode(segments[2]);
            if (!FormRoute.isKey(entityKey)) {
                return FormRouteParseResult.rejected("entityKey вне грамматики: '" + segments[2] + "'");
            }
            return FormRouteParseResult.parsed(new FormRoute(FormRouteKind.LIST, entityKey, null, variant));
        }
        return FormRouteParseResult.rejected("адрес не является маршрутом формы: '" + url + "'");
    }

    /** Результат разбора query-строки: либо вариант (может быть {@code null}), либо отказ. */
    private record VariantParse(String variant, String rejection) {
    }

    /**
     * Разбор query-строки: вариант плюс проверка, что других значимых параметров нет.
     * Пустой {@code rejection} означает успех.
     */
    private VariantParse variantOf(String query) {
        String variant = null;
        if (query == null) {
            return new VariantParse(null, null);
        }
        for (String parameter : query.split("&", -1)) {
            if (parameter.isEmpty()) {
                return new VariantParse(null, "пустой query-параметр");
            }
            int equals = parameter.indexOf('=');
            String name = equals < 0 ? parameter : parameter.substring(0, equals);
            String rawValue = equals < 0 ? "" : parameter.substring(equals + 1);
            if (CONTINUE_PARAMETER.equals(name)) {
                // Переносимый инфраструктурный параметр: форма его не видит.
                continue;
            }
            if (!VARIANT_PARAMETER.equals(name)) {
                return new VariantParse(null, "неизвестный query-параметр: '" + name + "'");
            }
            if (variant != null) {
                return new VariantParse(null, "variant задан дважды");
            }
            String decoded = decode(rawValue);
            if (!FormRoute.isKey(decoded)) {
                return new VariantParse(null, "variant вне грамматики: '" + rawValue + "'");
            }
            variant = decoded;
        }
        return new VariantParse(variant, null);
    }

    /**
     * Идентификатор: положительный десятичный {@code Long} без ведущих нулей и знака.
     * Возвращает {@code null} для любого отклонения (в том числе overflow), а не «сколько
     * получилось»: частично разобранный id — это другой адрес.
     */
    private static Long parseId(String raw) {
        String value = decode(raw);
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() > 1 && value.charAt(0) == '0') {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return null;
            }
        }
        try {
            long id = Long.parseLong(value);
            return id > 0 ? id : null;
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    /**
     * Однократное percent-decoding. Некорректная escape-последовательность — отказ
     * ({@code null}), а не «декодируем как получилось»: иначе адрес с ошибкой кодирования
     * выглядел бы как валидный и попадал в каталог.
     */
    private static String decode(String raw) {
        if (raw == null || raw.indexOf('%') < 0) {
            return raw;
        }
        StringBuilder result = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char current = raw.charAt(i);
            if (current != '%') {
                result.append(current);
                continue;
            }
            if (i + 2 >= raw.length()) {
                return null;
            }
            int high = Character.digit(raw.charAt(i + 1), 16);
            int low = Character.digit(raw.charAt(i + 2), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            result.append((char) (high * 16 + low));
            i += 2;
        }
        return result.toString();
    }
}
