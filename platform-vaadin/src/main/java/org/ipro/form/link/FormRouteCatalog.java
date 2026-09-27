package org.ipro.form.link;

import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.builder.ContextFilterField;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormType;
import org.ipro.metadata.FactOrigin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Каталог публикуемых адресов форм (E2.1, ADR-0009 §4): единственное место, которое решает,
 * какие типы получают публичный ключ, какие варианты у них есть и где ссылка не выдаётся.
 *
 * <p><b>Источник истины — существующие каталоги, а не второй реестр.</b> Публикуемость берётся
 * из {@link EntityDescriptorCatalog} (экспозиция типа: {@code STANDARD_ROOT} — да,
 * {@code OWNED_ROW}/{@code INTERNAL_STORE}/{@code UNCLASSIFIED} — нет), варианты — из
 * {@link FormRegistry} (там же остаются регистрации, второй mutable реестр форм не заводится).
 * Classpath scan здесь не выполняется.</p>
 *
 * <p><b>Ключ выводится, а не выбирается.</b> По умолчанию {@code entityKey} — ASCII
 * lower-kebab-case имени класса: {@code PrdSpec} → {@code prd-spec}, {@code SklNomOpa} →
 * {@code skl-nom-opa}. Явная декларация ({@link FormRouteAliasDeclaration}) меняет ключ только
 * с причиной и сохраняет прежние как legacy — так переименование Java-типа не ломает
 * опубликованный адрес.</p>
 *
 * <p><b>Построение — один раз, ошибки конфигурации — громко.</b> Повтор ключа, ключ вне
 * грамматики, декларация для непубликуемого типа и вариант с именем {@code default} (этот ключ
 * в реестре означает default-ветку, поэтому именованный вариант так называть нельзя)
 * собираются в одно исключение: композицию маршрутов дешевле починить всю сразу, а не
 * по одному конфликту за запуск.</p>
 *
 * <p><b>Два способа получить каталог.</b> {@link #build} строит его немедленно — так его видят
 * тесты и любой код, у которого оба каталога уже на руках. {@link #deferred} используется
 * wiring'ом: построение происходит при первом обращении, причём только после заморозки
 * реестра форм ({@link FormRegistry#isFrozen()}). Раннее обращение — не «пустой каталог», а
 * понятный отказ: молча неполный набор вариантов опаснее падения.</p>
 */
public final class FormRouteCatalog {

    private final Supplier<EntityDescriptorCatalog> descriptors;
    private final Supplier<FormRegistry> forms;
    private final List<FormRouteAliasDeclaration> declarations;
    private final boolean requireFrozenRegistry;
    private volatile Snapshot snapshot;

    private FormRouteCatalog(Supplier<EntityDescriptorCatalog> descriptors,
                             Supplier<FormRegistry> forms,
                             List<FormRouteAliasDeclaration> declarations,
                             boolean requireFrozenRegistry) {
        this.descriptors = descriptors;
        this.forms = forms;
        this.declarations = declarations == null ? List.of() : List.copyOf(declarations);
        this.requireFrozenRegistry = requireFrozenRegistry;
    }

    /** Каталог, построенный сразу: композиция уже на руках (тесты, ручная сборка). */
    public static FormRouteCatalog build(EntityDescriptorCatalog descriptors, FormRegistry forms,
                                         List<FormRouteAliasDeclaration> declarations) {
        Objects.requireNonNull(descriptors, "descriptors must not be null");
        Objects.requireNonNull(forms, "forms must not be null");
        FormRouteCatalog catalog = new FormRouteCatalog(() -> descriptors, () -> forms, declarations, false);
        catalog.snapshot = compile(descriptors, forms, catalog.declarations);
        return catalog;
    }

    /**
     * Каталог для wiring'а: строится при первом обращении, требует завершённой композиции форм.
     *
     * <p>Почему не в конструкторе бина: регистраторы вариантов — такие же бины, и порядок их
     * создания контейнером не определён. Снимок до их работы зафиксировал бы неполный набор
     * вариантов, причём молча.</p>
     */
    public static FormRouteCatalog deferred(Supplier<EntityDescriptorCatalog> descriptors,
                                            Supplier<FormRegistry> forms,
                                            List<FormRouteAliasDeclaration> declarations) {
        return deferred(descriptors, forms, declarations, true);
    }

    /**
     * То же, но с решением вызывающего о заморозке реестра (E2.1/E2.2).
     *
     * <p>{@code requireFrozenRegistry = false} — для приложения, которое осознанно разрешило
     * регистрацию форм во время работы ({@code ipro.form.registry.allow-runtime-registration}).
     * Требовать завершённой композиции там, где реестр намеренно оставлен открытым, значило бы
     * запрещать выбранный режим: до этого уточнения стенд с таким свойством падал на startup-проверке
     * каталога, потому что {@code validate()} вызывается по {@code ApplicationReadyEvent}, а реестр
     * к этому моменту ещё открыт. Снимок вариантов в этом режиме неполон по определению — это цена
     * режима, а не дефект каталога, и её оплачивает тот, кто включил регистрацию в рантайме.</p>
     */
    public static FormRouteCatalog deferred(Supplier<EntityDescriptorCatalog> descriptors,
                                            Supplier<FormRegistry> forms,
                                            List<FormRouteAliasDeclaration> declarations,
                                            boolean requireFrozenRegistry) {
        Objects.requireNonNull(descriptors, "descriptors must not be null");
        Objects.requireNonNull(forms, "forms must not be null");
        return new FormRouteCatalog(descriptors, forms, declarations, requireFrozenRegistry);
    }

    /** Построить каталог, если он ещё не построен: точка startup-проверки wiring'а. */
    public void validate() {
        require();
    }

    /** Публикуемые типы в порядке ключей. */
    public List<PublishedFormRoute> all() {
        return require().all();
    }

    /** Поиск по каноническому либо legacy-ключу. Регистр не нормализуется. */
    public Optional<PublishedFormRoute> find(String entityKey) {
        return Optional.ofNullable(require().byKey().get(entityKey));
    }

    /** Поиск по persistence-классу. */
    public Optional<PublishedFormRoute> find(Class<?> type) {
        return Optional.ofNullable(require().byType().get(type));
    }

    /** Поиск по разобранному маршруту: ключ адреса обязан быть опубликован. */
    public Optional<PublishedFormRoute> find(FormRoute route) {
        return find(route.entityKey());
    }

    /** Число публикуемых типов. */
    public int size() {
        return require().all().size();
    }

    /**
     * Причина, по которой адрес этого вида формы не строится, либо {@code null}, если строится
     * (E2.1, вход решения E1).
     *
     * <p>Ответ строится по типу и виду формы, а не по записи: каталог знает, <i>публикуется</i> ли
     * тип, разрешён ли ему сценарий чтения карточки и линкабелен ли вариант, но не знает, есть ли у
     * конкретной записи id. Поэтому «адреса нет, потому что запись не сохранена»
     * ({@link NotLinkableReason#MISSING_ID}) — ответ вызывающего, а не каталога: различать эти
     * случаи обязательно, иначе несохранённая карточка считалась бы неадресуемой по типу.</p>
     *
     * <p>{@code null} вместо {@code Optional} — потому что вопроса два, а не три: «строится» и
     * «не строится, и вот почему». Третьего состояния у адреса нет. Отдельно от {@code null}
     * стоит отсутствие входа в самом контексте решения — это уже не про адрес, а про композицию.</p>
     */
    public NotLinkableReason notLinkable(Class<?> type, FormRouteKind kind, String variant) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        PublishedFormRoute published = find(type).orElse(null);
        if (published == null) {
            return NotLinkableReason.NOT_PUBLISHED;
        }
        return published.notLinkable(kind, variant).orElse(null);
    }

    private Snapshot require() {
        Snapshot current = snapshot;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (snapshot == null) {
                FormRegistry registry = forms.get();
                if (requireFrozenRegistry && !registry.isFrozen()) {
                    throw new IllegalStateException("Каталог маршрутов строится после завершения"
                        + " композиции форм: реестр FormRegistry ещё открыт для регистраций."
                        + " Обращение к маршрутам во время старта дало бы неполный набор вариантов,"
                        + " поэтому это отказ, а не пустой каталог.");
                }
                snapshot = compile(descriptors.get(), registry, declarations);
            }
            return snapshot;
        }
    }

    private record Snapshot(List<PublishedFormRoute> all,
                            Map<String, PublishedFormRoute> byKey,
                            Map<Class<?>, PublishedFormRoute> byType) {
    }

    /** Черновик записи: пока не решены конфликты ключей, публиковать его нельзя. */
    private record Draft(Class<?> type, EntityDescriptor descriptor, String entityKey,
                         List<String> legacyKeys, FactOrigin keyOrigin,
                         String keyReason, String keySymbol) {

        List<String> keys() {
            List<String> keys = new ArrayList<>(legacyKeys.size() + 1);
            keys.add(entityKey);
            keys.addAll(legacyKeys);
            return keys;
        }
    }

    private static Snapshot compile(EntityDescriptorCatalog descriptors, FormRegistry forms,
                                    List<FormRouteAliasDeclaration> declarations) {
        List<String> problems = new ArrayList<>();

        Map<Class<?>, FormRouteAliasDeclaration> declared = new LinkedHashMap<>();
        for (FormRouteAliasDeclaration declaration : declarations) {
            if (declared.putIfAbsent(declaration.type(), declaration) != null) {
                problems.add("декларация ключа для " + declared.get(declaration.type()).type().getSimpleName()
                    + " объявлена дважды");
            }
            if (declaration.entityKey() != null && !FormRoute.isKey(declaration.entityKey())) {
                problems.add("alias '" + declaration.entityKey() + "' вне грамматики"
                    + " (ASCII lower-kebab-case)");
            }
            for (String legacy : declaration.legacyKeys()) {
                if (!FormRoute.isKey(legacy)) {
                    problems.add("legacy-ключ '" + legacy + "' вне грамматики");
                }
            }
        }

        List<Draft> drafts = new ArrayList<>();
        for (EntityDescriptor descriptor : descriptors.all()) {
            if (descriptor.exposure() != EntityExposure.STANDARD_ROOT) {
                continue;
            }
            Class<?> type = descriptor.type();
            FormRouteAliasDeclaration declaration = declared.remove(type);
            String entityKey = declaration != null && declaration.entityKey() != null
                ? declaration.entityKey()
                : kebabCase(type.getSimpleName());
            List<String> legacyKeys = declaration == null ? List.of() : declaration.legacyKeys();
            boolean explicitKey = declaration != null && declaration.entityKey() != null;
            drafts.add(new Draft(type, descriptor, entityKey, legacyKeys,
                explicitKey ? FactOrigin.REGISTRATION : FactOrigin.DERIVED,
                explicitKey ? declaration.reason() : "generated from class simple name",
                explicitKey ? "" : type.getName()));
        }
        for (FormRouteAliasDeclaration orphan : declared.values()) {
            problems.add("декларация ключа для непубликуемого типа "
                + orphan.type().getSimpleName() + ": публикуется только STANDARD_ROOT"
                + " (OWNED_ROW и INTERNAL_STORE адреса не получают)");
        }

        Map<String, Class<?>> claimed = new LinkedHashMap<>();
        for (Draft draft : drafts) {
            for (String key : draft.keys()) {
                Class<?> previous = claimed.putIfAbsent(key, draft.type());
                if (previous != null && previous != draft.type()) {
                    problems.add("ключ '" + key + "' занят двумя типами: "
                        + previous.getSimpleName() + " и " + draft.type().getSimpleName());
                }
            }
        }

        List<PublishedFormRoute> routes = new ArrayList<>(drafts.size());
        for (Draft draft : drafts) {
            Class<?> type = draft.type();
            Set<String> registeredItems = registeredVariants(forms, type, FormType.ITEM);
            Set<String> registeredLists = registeredVariants(forms, type, FormType.LIST);
            if (registeredItems.contains(PublishedFormRoute.DEFAULT_VARIANT)) {
                problems.add("вариант с именем 'default' зарегистрирован для " + type.getSimpleName()
                    + " (ITEM): в реестре default-ветка — это null, поэтому такой ключ неоднозначен");
            }
            if (registeredLists.contains(PublishedFormRoute.DEFAULT_VARIANT)) {
                problems.add("вариант с именем 'default' зарегистрирован для " + type.getSimpleName()
                    + " (LIST): в реестре default-ветка — это null, поэтому такой ключ неоднозначен");
            }
            Set<String> itemVariants = withDefault(registeredItems);
            Set<String> listVariants = withDefault(registeredLists);

            PublishedFormRoute.Blockers blockers = new PublishedFormRoute.Blockers();
            if (!draft.descriptor().capabilities().allows(FetchScenario.DETAIL)) {
                blockers.block(FormRouteKind.ITEM, null, NotLinkableReason.SCENARIO_NOT_ALLOWED);
            }
            if (!draft.descriptor().capabilities().allows(FetchScenario.LIST)) {
                blockers.block(FormRouteKind.LIST, null, NotLinkableReason.SCENARIO_NOT_ALLOWED);
            } else {
                for (String variant : listVariants) {
                    if (requiresContext(forms, type, variant)) {
                        blockers.block(FormRouteKind.LIST, variant, NotLinkableReason.REQUIRED_CONTEXT);
                    }
                }
            }
            routes.add(new PublishedFormRoute(draft.entityKey(), type, draft.legacyKeys(),
                itemVariants, listVariants, blockers.map(), draft.keyOrigin(),
                draft.keyReason(), draft.keySymbol()));
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Каталог маршрутов не построен — композиция адресов"
                + " противоречива: " + String.join("; ", problems));
        }

        routes.sort(java.util.Comparator.comparing(PublishedFormRoute::entityKey));
        Map<String, PublishedFormRoute> byKey = new LinkedHashMap<>();
        Map<Class<?>, PublishedFormRoute> byType = new LinkedHashMap<>();
        for (PublishedFormRoute route : routes) {
            for (String key : route.keys()) {
                byKey.put(key, route);
            }
            byType.put(route.entityClass(), route);
        }
        return new Snapshot(List.copyOf(routes), Map.copyOf(byKey), Map.copyOf(byType));
    }

    /**
     * Именованные варианты вида формы по снимку регистраций. Default-ветка сюда не входит: она
     * проверяется отдельно, потому что её ключ в реестре — {@code null}, а не {@code "default"}.
     */
    private static Set<String> registeredVariants(FormRegistry forms, Class<?> type, FormType formType) {
        Set<String> variants = new TreeSet<>();
        for (FormRegistry.Registration registration : forms.registrationsOf(type)) {
            if (registration.formType() == formType && registration.variant() != null) {
                variants.add(registration.variant());
            }
        }
        return variants;
    }

    /** Публикуемый набор вариантов: именованные плюс default, который резолвится generic-путём. */
    private static Set<String> withDefault(Set<String> registered) {
        Set<String> variants = new TreeSet<>(registered);
        variants.add(PublishedFormRoute.DEFAULT_VARIANT);
        return variants;
    }

    /**
     * Обязательный контекст списка — по тому же ряду фильтров, что видит пользователь:
     * ряд варианта, если он объявлен, иначе общий ряд сущности.
     */
    private static boolean requiresContext(FormRegistry forms, Class<?> type, String variant) {
        String registryVariant = PublishedFormRoute.DEFAULT_VARIANT.equals(variant) ? null : variant;
        return forms.resolveListContextFilters(type, registryVariant).stream()
            .anyMatch(ContextFilterField::required);
    }

    /**
     * ASCII lower-kebab-case имени класса: граница — перед заглавной после строчной/цифры,
     * а также перед заглавной, открывающей слово внутри аббревиатуры ({@code XMLParser} →
     * {@code xml-parser}).
     */
    static String kebabCase(String simpleName) {
        StringBuilder result = new StringBuilder(simpleName.length() + 4);
        for (int i = 0; i < simpleName.length(); i++) {
            char current = simpleName.charAt(i);
            if (Character.isUpperCase(current)) {
                char previous = i == 0 ? 0 : simpleName.charAt(i - 1);
                boolean boundary = i > 0 && (!Character.isUpperCase(previous)
                    || i + 1 < simpleName.length() && Character.isLowerCase(simpleName.charAt(i + 1)));
                if (boundary) {
                    result.append('-');
                }
                result.append(Character.toLowerCase(current));
            } else {
                result.append(current);
            }
        }
        return result.toString();
    }
}
