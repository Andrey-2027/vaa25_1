package org.ipro.form.action;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Происхождение действий приложения (E3.2.0): где объявлено определение и кто его исполняет.
 *
 * <p>Каталог стоит <b>в месте сборки</b> {@link ActionRegistry} — в
 * {@code FormAutoConfiguration.actionRegistry} — потому что только там одновременно видны все три
 * источника: платформенные defaults, прикладные {@link ActionHandler}-бины и определения-бины
 * приложения. Ни UI, ни сборщик сводки не могут восстановить это знание задним числом: у
 * {@link ActionDefinition} места объявления нет, а имя конфигурации по имени действия не
 * угадывается.</p>
 *
 * <p><b>Ключ, а не инстанс.</b> Карта происхождения ведётся по {@link ActionRegistry.Key}:
 * {@link CrudAction#platformDefaults()} создаёт новые объекты на каждом вызове, поэтому
 * «происхождение инстанса» было бы ложным уже на втором вызове. Ключи уникальны (дубликат роняет
 * старт), поэтому запись происхождения указывает на одну запись реестра.</p>
 *
 * <p><b>Почему разворачивание proxy живёт здесь, а не в UI.</b> Тот же довод, что у
 * {@code EntityLifecycleInspection} в E3.1: владелец реестра знает, чем обёрнут бин, а слой
 * отображения обязан оставаться читателем фактов. Разворачивание идёт
 * {@link ClassUtils#getUserClass} ({@code spring-core}); JDK-proxy им не разворачивается, и это
 * не дефект, а измеренная граница: такой исполнитель даёт пустой символ и примечание
 * «место объявления не подтверждено», а не имя proxy-класса, которого нет в исходниках.</p>
 *
 * <p><b>Сверка на старте.</b> Каталог падает, если состав его записей не совпадает с составом
 * реестра: «регистрация без происхождения» и «происхождение без регистрации» одинаково означали
 * бы, что карточка объясняет не то, что исполняется. Проверка идёт при сборке, а не при показе.</p>
 *
 * @see ActionProvenance
 */
public final class ActionProvenanceCatalog {

    /** Платформенный состав объявлен ровно здесь, поэтому и символ у него ровно один. */
    private static final String PLATFORM_SYMBOL = CrudAction.class.getName();

    private static final String PLATFORM_NOTE = "платформенное действие, применимо к любому типу";
    private static final String PROXY_NOTE = "место объявления не подтверждено";
    private static final String NOT_DECLARED_NOTE = "место объявления не сообщается";
    private static final String NO_EXECUTOR_NOTE = "исполнитель не зарегистрирован";

    private final Map<ActionRegistry.Key, ActionProvenance> declarations;
    private final Map<ActionRegistry.Key, ActionProvenance> executors;

    /**
     * @param declarations происхождение объявлений по ключу регистрации
     * @param executors    происхождение исполнителей по тому же ключу
     */
    public ActionProvenanceCatalog(Map<ActionRegistry.Key, ActionProvenance> declarations,
                                   Map<ActionRegistry.Key, ActionProvenance> executors) {
        this.declarations = Collections.unmodifiableMap(new LinkedHashMap<>(
            Objects.requireNonNull(declarations, "declarations")));
        this.executors = Collections.unmodifiableMap(new LinkedHashMap<>(
            Objects.requireNonNull(executors, "executors")));
    }

    /**
     * Сборка каталога из трёх источников — то же место, где собирается реестр.
     *
     * @param registry        собранный реестр: его состав каталог обязан покрыть целиком
     * @param beanDefinitions определения-бины приложения (имя бина → определение): имя нужно, чтобы
     *                        назвать класс-объявление {@code @Bean}-метода
     * @param handlerRegistry прикладные исполнители: их класс и есть место объявления действия
     * @param beanFactory     фабрика, умеющая назвать класс-объявление для бина
     */
    public static ActionProvenanceCatalog ofBeans(ActionRegistry registry,
                                                  Map<String, ActionDefinition> beanDefinitions,
                                                  ActionHandlerRegistry handlerRegistry,
                                                  ConfigurableListableBeanFactory beanFactory) {
        Objects.requireNonNull(registry, "registry must not be null");
        Objects.requireNonNull(beanDefinitions, "beanDefinitions must not be null");
        Objects.requireNonNull(handlerRegistry, "handlerRegistry must not be null");
        Objects.requireNonNull(beanFactory, "beanFactory must not be null");

        Map<ActionRegistry.Key, ActionProvenance> declarations = new LinkedHashMap<>();
        Map<ActionRegistry.Key, ActionProvenance> executors = new LinkedHashMap<>();

        for (ActionDefinition definition : CrudAction.platformDefaults()) {
            declarations.put(keyOf(definition),
                ActionProvenance.platformDefault(PLATFORM_SYMBOL, PLATFORM_NOTE));
        }
        for (ActionHandler handler : handlerRegistry.handlers()) {
            ActionProvenance provenance = handlerProvenance(handler);
            declarations.put(keyOf(handler.definition()), provenance);
            executors.put(keyOf(handler.definition()), provenance);
        }
        for (Map.Entry<String, ActionDefinition> bean : beanDefinitions.entrySet()) {
            declarations.put(keyOf(bean.getValue()), declaringBeanProvenance(bean.getKey(), beanFactory));
        }

        verify(registry, declarations);
        return new ActionProvenanceCatalog(declarations, executors);
    }

    /**
     * Происхождение объявления для ключа регистрации.
     *
     * <p>Пускается потребителем с ключом <b>победившей</b> регистрации
     * ({@link ActionRegistry#registration}), а не с «похожим» ключом: происхождение принадлежит
     * записи, а не типу сущности. Записи, которой каталог не знает, он не выдумывает.</p>
     */
    public ActionProvenance declarationOf(ActionRegistry.Key key) {
        Objects.requireNonNull(key, "key must not be null");
        return declarations.getOrDefault(key, ActionProvenance.unattributed(NOT_DECLARED_NOTE));
    }

    /**
     * Происхождение исполнителя для того же ключа. Действие без исполнителя — не «сломанное»:
     * регистрация есть, а исполнять нечего, и это сказано примечанием, а не пустой ячейкой.
     */
    public ActionProvenance executorOf(ActionRegistry.Key key) {
        Objects.requireNonNull(key, "key must not be null");
        return executors.getOrDefault(key,
            ActionProvenance.platformDefault("", NO_EXECUTOR_NOTE));
    }

    private static ActionProvenance handlerProvenance(ActionHandler handler) {
        Class<?> handlerType = ClassUtils.getUserClass(handler);
        if (Proxy.isProxyClass(handlerType)) {
            return ActionProvenance.registration("", PROXY_NOTE);
        }
        return ActionProvenance.registration(handlerType.getName(), "");
    }

    /**
     * Класс-объявление для определения-бина. {@code @Bean}-метод объявлен в методе конфигурации,
     * поэтому место — фабрикующий бин, а не сам класс определения: {@code ActionDefinition} в
     * символе не сообщил бы ничего.
     *
     * <p>Проксируемая конфигурация отдаёт CGLIB-подкласс, поэтому сырое значение разворачивается
     * {@link ClassUtils#getUserClass} — измерено в шаге 1.1. Не разрешилось (бин зарегистрирован не
     * {@code @Bean}-методом, фабрикующий класс не виден) — пустой символ, а не догадка.</p>
     */
    private static ActionProvenance declaringBeanProvenance(
            String beanName, ConfigurableListableBeanFactory beanFactory) {
        Class<?> declaring = declaringClassOf(beanName, beanFactory);
        return declaring == null
            ? ActionProvenance.registration("", NOT_DECLARED_NOTE)
            : ActionProvenance.registration(declaring.getName(), "");
    }

    private static Class<?> declaringClassOf(String beanName, ConfigurableListableBeanFactory beanFactory) {
        try {
            String factoryBeanName = beanFactory.getBeanDefinition(beanName).getFactoryBeanName();
            if (factoryBeanName == null) {
                return null;
            }
            Class<?> factoryType = beanFactory.getType(factoryBeanName);
            return factoryType == null ? null : ClassUtils.getUserClass(factoryType);
        } catch (BeansException unavailable) {
            return null;
        }
    }

    /**
     * Состав происхождения обязан совпасть с составом реестра: иначе карточка показывала бы
     * действия, которых нет, либо молчала о тех, что есть.
     */
    private static void verify(ActionRegistry registry, Map<ActionRegistry.Key, ActionProvenance> declarations) {
        List<ActionRegistry.Registration> registrations = registry.registrationsWithKeys();
        if (registrations.size() != declarations.size()) {
            throw new IllegalStateException("Происхождение действий не покрывает реестр: регистраций "
                + registrations.size() + ", записей происхождения " + declarations.size());
        }
        for (ActionRegistry.Registration registration : registrations) {
            if (!declarations.containsKey(registration.key())) {
                throw new IllegalStateException("Регистрация без происхождения: "
                    + describe(registration.key()));
            }
        }
    }

    private static ActionRegistry.Key keyOf(ActionDefinition definition) {
        return ActionRegistry.Key.of(definition);
    }

    private static String describe(ActionRegistry.Key key) {
        return "surface=" + key.surface()
            + ", entityType=" + (key.entityType() == null ? "*" : key.entityType().getSimpleName())
            + ", variant=" + (key.variant() == null ? "*" : key.variant())
            + ", id=" + key.id();
    }
}
