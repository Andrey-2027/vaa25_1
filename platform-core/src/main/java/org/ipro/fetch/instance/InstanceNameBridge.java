package org.ipro.fetch.instance;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Platform-internal static bridge к {@link InstanceNameProvider} для утилит, которые не
 * являются Spring-бинами (форматирование значения грида, снимок для аудита, подпись
 * каталога) и вызываются из static-контекста.
 *
 * <p>Рендеринг идёт ТОЛЬКО через {@link InstanceNameProvider#resolve(Object)}: bridge не
 * содержит своей лестницы имён. Вторая лестница — это не дублирование, а дефект: канал,
 * обходивший proxy-safe реализацию, падал на detached-ссылке
 * ({@code LazyInitializationException}) и показывал другое имя, чем UI и аудит
 * (см. ADR-0006). Без установленного провайдера (юнит-тесты, не-Spring контекст)
 * используется та же лестница в статической форме —
 * {@link InstanceNameResolver#compatibleDisplayName(Object)}.</p>
 *
 * <p>Жизненный цикл регистрации привязан к контексту и регистрации <b>сосуществуют</b>, а
 * не перезаписывают друг друга: {@link InstanceNameBridgeInstaller} при старте добавляет
 * свою регистрацию, при закрытии снимает ровно её. Это важно, потому что в одной JVM
 * живёт не один контекст: приложение, кэшированные тест-контексты, вытесняемые из кэша
 * Spring, частичные контексты auto-configuration. При перезаписи закрытие любого из них
 * обнуляло глобальное состояние для остальных.</p>
 *
 * <p>Активность регистрации решается <b>по объявлению типа</b>, а не по одной очереди
 * установки: для значения выбирается самая поздняя регистрация, чей анализатор объявляет
 * тип этого значения, и лишь если таких нет — самая поздняя вообще. Выбор «всегда
 * последняя» делал авторитетным любой контекст, поднявшийся позже, — включая частичный
 * (его анализатор не знает объявлений). Наблюдалось как потеря единого имени в UI и
 * аудите: вместо «РН-ПИЛОТ от 2026-09-13» канал отображения получал fallback-ссылку
 * {@code ReceivingDocument}, хотя живой контекст объявление знал.</p>
 */
public final class InstanceNameBridge {

    private record Registration(InstanceNameProvider provider, InstanceNameResolver analysis) {
    }

    /**
     * Регистрации живых контекстов, от ранней к поздней. Активна последняя. Список
     * copy-on-write: чтение идёт на каждом рендере имени, установка — только на старте
     * и закрытии контекста.
     */
    private static final List<Registration> REGISTRATIONS = new CopyOnWriteArrayList<>();

    private InstanceNameBridge() {
    }

    /**
     * Устанавливает провайдера рендеринга и анализатор состава имени. Разделены намеренно:
     * состав имени и допустимые fetch-пути выводятся из metadata платформы, поэтому
     * пользовательский {@link InstanceNameProvider} меняет представление, но не может
     * сделать fetch-планы недоступными.
     */
    public static void install(InstanceNameProvider provider, InstanceNameResolver analysis) {
        Registration registration = new Registration(provider, analysis);
        for (Registration existing : REGISTRATIONS) {
            if (existing.provider() == provider && existing.analysis() == analysis) {
                return; // повторная установка того же контекста — не плодим регистрации
            }
        }
        REGISTRATIONS.add(registration);
    }

    /** Платформенный вариант: и рендеринг, и анализ берутся из резолвера. */
    public static void install(InstanceNameResolver resolver) {
        install(resolver, resolver);
    }

    /** Текущий провайдер рендеринга — для диагностики и тестов, обязанных восстановить
     * глобальное состояние после проверки своего сценария. */
    public static InstanceNameProvider installedProvider() {
        Registration active = current();
        return active == null ? null : active.provider();
    }

    /** Текущий анализатор состава имени — парный к {@link #installedProvider()}. */
    public static InstanceNameResolver installedAnalysis() {
        Registration active = current();
        return active == null ? null : active.analysis();
    }

    /**
     * Снимает регистрацию, только если она всё ещё принадлежит этому установщику: иначе
     * закрывающийся старый контекст удалил бы провайдера нового.
     */
    public static void uninstall(InstanceNameProvider installedProvider,
                                 InstanceNameResolver installedAnalysis) {
        REGISTRATIONS.removeIf(registration -> registration.provider() == installedProvider
            && registration.analysis() == installedAnalysis);
    }

    /** Регистрация активного контекста или {@code null}, если контекстов нет вовсе. */
    private static Registration current() {
        return REGISTRATIONS.isEmpty() ? null : REGISTRATIONS.get(REGISTRATIONS.size() - 1);
    }

    /**
     * Регистрация, авторитетная для конкретного значения: самая поздняя, чей анализатор
     * объявляет его тип; иначе — самая поздняя. Тип берётся без разворота Hibernate-прокси,
     * поэтому выбор регистрации не может инициировать ленивую загрузку.
     */
    private static Registration currentFor(Class<?> type) {
        for (int index = REGISTRATIONS.size() - 1; index >= 0; index--) {
            Registration candidate = REGISTRATIONS.get(index);
            if (candidate.analysis().canResolve(type)) {
                return candidate;
            }
        }
        return current();
    }

    private static Registration currentFor(Object value) {
        return currentFor(InstanceNameResolver.persistentClass(value));
    }

    /**
     * Единое отображаемое имя значения — единственный разрешённый путь для каналов
     * отображения (ADX-09). Никогда не инициирует lazy load и не бросает
     * {@code LazyInitializationException}.
     */
    public static String displayName(Object value) {
        if (value == null) {
            return "";
        }
        Registration active = currentFor(value);
        return active == null
            ? InstanceNameResolver.compatibleDisplayName(value)
            : active.provider().resolve(value);
    }

    /**
     * Имя мигрированной сущности или {@code null}, если у типа нет декларации —
     * потребители, обязанные сохранить legacy-поведение для остальных, различают эти
     * случаи по {@code null}.
     */
    public static String declaredName(Object entity) {
        Registration active = currentFor(entity);
        return active == null ? null : active.analysis().declaredName(entity);
    }

    /** Состав имени мигрированной сущности; пустой список, если декларации/анализатора нет. */
    public static List<String> instanceNamePaths(Class<?> entityClass) {
        Registration active = currentFor(entityClass);
        return active == null ? List.of() : active.analysis().instanceNamePaths(entityClass);
    }

    /** Зависимости имени мигрированной сущности; пустой список без декларации/анализатора. */
    public static List<String> instanceNameFetchPaths(Class<?> entityClass) {
        Registration active = currentFor(entityClass);
        return active == null ? List.of() : active.analysis().instanceNameFetchPaths(entityClass);
    }

    /**
     * Объявлен ли у класса единый источник имени. Чистая рефлексия — не зависит от того,
     * установлен ли провайдер, поэтому пригодна для startup-валидации.
     */
    public static boolean hasDeclaration(Class<?> entityClass) {
        return InstanceNameResolver.declares(entityClass);
    }
}
