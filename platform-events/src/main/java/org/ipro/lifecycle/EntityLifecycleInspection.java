package org.ipro.lifecycle;

import org.springframework.aop.support.AopUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Инспекция прикладного lifecycle handler'а: какие хуки {@link EntityLifecycle} он реально
 * переопределяет, а какие наследует как default-методы контракта.
 *
 * <p>Знание о составе хуков принадлежит модулю-владельцу реестра: {@link EntityLifecycle}
 * и {@link EntityLifecycleRegistry} живут здесь, а контракты платформы
 * ({@code platform-contracts}) намеренно не зависят от Spring и не умеют разворачивать proxy.
 * Инспекция отдаёт факты — целевой класс и признак переопределения, — а тексты и происхождение
 * факта собирает потребитель (Entity Explorer), чтобы модуль событий не знал слоя отображения.</p>
 *
 * <p><b>Что инспекция не делает.</b> Делегирование не доказывается: если handler передаёт
 * правило другому бину, вызванному из своего хука, это остаётся внутри тела метода и снаружи
 * невидимо. Поэтому ответ всегда говорит о самом handler'е, а не о полном составе правил
 * сущности. По той же причине инструментально сгенерированный подкласс handler'а
 * (ByteBuddy, mock) неотличим от рукописного: инспекция видит переопределение метода, а не
 * его происхождение — это граница метода, а не дефект.</p>
 *
 * <p>Разворачивание Spring-proxy ({@link AopUtils#getTargetClass}) даёт целевой класс и для
 * JDK-, и для CGLIB-proxy. Proxy, созданный вне Spring ({@link java.lang.reflect.Proxy}),
 * развернуть нечем: такой handler даёт {@link Optional#empty()} — «происхождение хуков не
 * подтверждено», а не «все хуки default». Без этого различия proxy молча выглядел бы как
 * handler без переопределений.</p>
 */
public final class EntityLifecycleInspection {

    /**
     * Порядок хуков — часть ответа, а не деталь обхода рефлексии. Список совпадает с порядком
     * объявления {@link EntityLifecycle} и проверяется guard-тестом на set-равенство с публичными
     * методами контракта минус {@code entityType} (не hook). Незнакомое имя в списке —
     * fail-fast при загрузке класса, а не тихое усечение ответа.
     */
    private static final List<String> HOOK_NAMES = List.of(
        "beforeSave", "beforeUpdate", "beforeAggregateSave", "beforeDelete", "onSave", "afterCommit");

    private static final List<Method> HOOK_METHODS = hookMethods();

    private EntityLifecycleInspection() {
    }

    /** Хук контракта: имя и переопределён ли он handler'ом. */
    public record Hook(String name, boolean declared) {
        public Hook {
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * Ответ инспекции по одному handler'у.
     *
     * @param handlerType целевой класс handler'а (после разворачивания proxy)
     * @param hooks       все хуки контракта в порядке объявления
     */
    public record Assessment(Class<?> handlerType, List<Hook> hooks) {
        public Assessment {
            Objects.requireNonNull(handlerType, "handlerType");
            hooks = List.copyOf(hooks);
        }
    }

    /**
     * Инспекция handler'а.
     *
     * @return {@link Optional#empty()}, если класс развернуть не удалось (Java-proxy вне Spring);
     *     иначе — целевой класс и состав переопределённых хуков
     */
    public static Optional<Assessment> of(EntityLifecycle<?> handler) {
        Objects.requireNonNull(handler, "handler must not be null");
        Class<?> handlerType = AopUtils.getTargetClass(handler);
        if (Proxy.isProxyClass(handlerType)) {
            return Optional.empty();
        }
        List<Hook> hooks = new ArrayList<>(HOOK_METHODS.size());
        for (Method hook : HOOK_METHODS) {
            hooks.add(new Hook(hook.getName(), isDeclared(handlerType, hook)));
        }
        return Optional.of(new Assessment(handlerType, hooks));
    }

    /**
     * Переопределение решается по целевому классу, а не по переданному объекту: наследование
     * default-метода даёт объявляющий класс {@link EntityLifecycle}, честное переопределение —
     * сам handler или его предок.
     */
    private static boolean isDeclared(Class<?> handlerType, Method hook) {
        try {
            Method resolved = handlerType.getMethod(hook.getName(), hook.getParameterTypes());
            return !EntityLifecycle.class.equals(resolved.getDeclaringClass());
        } catch (NoSuchMethodException missing) {
            throw new IllegalStateException(
                "EntityLifecycle handler " + handlerType.getName()
                    + " does not expose hook " + hook.getName(), missing);
        }
    }

    private static List<Method> hookMethods() {
        Map<String, Method> contract = new LinkedHashMap<>();
        for (Method method : EntityLifecycle.class.getMethods()) {
            if (method.getDeclaringClass() == EntityLifecycle.class
                    && !"entityType".equals(method.getName())) {
                contract.put(method.getName(), method);
            }
        }
        List<Method> ordered = new ArrayList<>(HOOK_NAMES.size());
        for (String name : HOOK_NAMES) {
            Method method = contract.get(name);
            if (method == null) {
                throw new IllegalStateException("EntityLifecycle no longer declares hook " + name);
            }
            ordered.add(method);
        }
        return List.copyOf(ordered);
    }
}
