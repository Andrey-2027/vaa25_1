package org.ip.form.coordinator;

import org.ipro.form.coordinator.FormCoordinator;
import org.ipro.form.coordinator.ItemFormWrapperView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Lazy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3.5.3: забор на UI-scope координатора — по <b>факту регистрации бина</b>, а не по аннотации
 * на классе.
 *
 * <p>Почему это отдельный тест от {@code FormCoordinatorUiIsolationTest}. Тот проверяет аннотацию
 * и независимость двух вручную созданных объектов — полезно, но недостаточно: аннотация на классе
 * игнорируется, если бин регистрируется {@code @Bean}-методом, а два объекта, созданных
 * {@code new}, не делят поля по определению. Здесь тот же вопрос задан контейнеру:</p>
 * <ol>
 *   <li>скоуп <b>объявленного бина</b> {@code formCoordinator} — {@code vaadin-ui}, и он не
 *       синглтон: иначе {@code workspace} одного UI виден другому, то есть P1 не закрыт;</li>
 *   <li>ни один синглтон не внедряет UI-scoped бин напрямую. У {@code @UIScope} нет scoped-proxy,
 *       значит такое внедрение либо роняет старт, либо захватывает UI первого пользователя.
 *       Единственный разрешённый способ — ленивый ({@code ObjectProvider}/{@code Provider}/
 *       {@code @Lazy}).</li>
 * </ol>
 *
 * <p>Второе правило — то, ради чего забор существует: {@code MainLayout} раньше не был бином
 * вообще, и его «пер-навигационность» держалась на фолбэке
 * {@code AutowireCapableBeanFactory.createBean}. Добавление {@code @SpringComponent} без
 * {@code @Scope("prototype")} превратило бы layout в синглтон с UI-scoped {@code WorkspaceManager}
 * — внешне безобидная правка, ломающая изоляцию UI.</p>
 */
@SpringBootTest
class FormCoordinatorScopeGuardTest {

    private static final String UI_SCOPE = "vaadin-ui";

    /** Классы, в которые Spring заворачивает зависимость, чтобы разрешить её при вызове. */
    private static final List<String> LAZY_WRAPPERS = List.of(
        ObjectProvider.class.getName(),
        "jakarta.inject.Provider",
        "org.springframework.beans.factory.Provider");

    @Autowired
    private ConfigurableApplicationContext context;

    @Test
    void coordinatorIsAUiScopedBeanAndNotASingleton() {
        List<String> names = definitionNamesOfType(beanFactory(), FormCoordinator.class);

        assertThat(names)
            .as("в контексте обязан быть ровно один бин координатора. Ноль — формовый слой не"
                + " поднялся (тогда забор вакуумный), больше одного — неоднозначная навигация")
            .hasSize(1);
        BeanDefinition definition = beanFactory().getBeanDefinition(names.get(0));

        assertThat(definition.getScope())
            .as("снятие @UIScope (или регистрация координатора через @Bean-метод, который"
                + " игнорирует аннотацию класса) возвращает межсессионный дефект: workspace одного"
                + " UI становится виден другому")
            .isEqualTo(UI_SCOPE);
        assertThat(definition.isSingleton())
            .as("координатор обязан быть UI-scoped, а не синглтоном")
            .isFalse();
    }

    /**
     * D3.5.5: тот же вопрос про скоуп, но для {@code ItemFormWrapperView} — и это единственное
     * место, где потеря {@code prototype} не была бы замечена ничем другим.
     *
     * <p>Скоуп объявлен на классе, а регистрация D3.5.5 перенесена в {@code @Bean}-метод, где
     * аннотация класса не наследуется: скоуп читается с фабричного метода. Для координатора это
     * ловит тест выше, а для view — никто: workspace-ветка создаёт представление через
     * {@code AutowireCapableBeanFactory.createBean} (мимо контейнера), поэтому там потеря скоупа
     * не видна в принципе. Единственный потребитель, который берёт его как бин, —
     * {@code DialogWorkspaceResolutionIT}, и он берёт его дважды через {@code ObjectProvider},
     * молча получая один и тот же инстанс на второй вызов.</p>
     *
     * <p>Почему это не косметика: вкладка формы хранит разобранную сущность и состояние черновика.
     * Синглтон вместо прототипа означает, что вторая открытая форма получает состояние первой.</p>
     */
    @Test
    void itemFormWrapperViewIsAPrototypeAndNotASingleton() {
        List<String> names = definitionNamesOfType(beanFactory(), ItemFormWrapperView.class);

        assertThat(names)
            .as("в контексте обязан быть ровно один бин представления формы: ноль — формовый слой"
                + " не поднялся, больше одного — неоднозначный выбор представления")
            .hasSize(1);
        BeanDefinition definition = beanFactory().getBeanDefinition(names.get(0));

        assertThat(definition.isPrototype())
            .as("снятие @Scope(\"prototype\") с класса (или регистрация через @Bean-метод без"
                + " явного скоупа) делает представление формы общим: вторая открытая форма получит"
                + " состояние первой")
            .isTrue();
        assertThat(definition.isSingleton()).isFalse();
    }

    @Test
    void noSingletonBeanInjectsAUiScopedBeanDirectly() {
        ConfigurableListableBeanFactory factory = beanFactory();
        Set<Class<?>> uiScopedTypes = uiScopedTypes(factory);

        assertThat(uiScopedTypes)
            .as("проверка не должна быть вакуумной: UI-scoped бины обязаны быть найдены, иначе"
                + " правило ничего не запрещает")
            .isNotEmpty();

        Map<String, String> violations = new TreeMap<>();
        int inspected = 0;
        for (String name : factory.getBeanDefinitionNames()) {
            BeanDefinition definition = factory.getBeanDefinition(name);
            if (!definition.isSingleton()) {
                continue;
            }
            Class<?> type = resolveType(factory, definition);
            if (type == null || type.isSynthetic() || !isProjectClass(type)) {
                continue;
            }
            inspected++;
            for (InjectionPoint point : injectionPoints(factory, definition, type)) {
                if (point.lazy() || isLazyWrapper(point.rawType())) {
                    continue;
                }
                for (Class<?> uiScoped : uiScopedTypes) {
                    if (uiScoped.isAssignableFrom(point.rawType())) {
                        violations.put(name + " → " + point.description(),
                            "внедряет UI-scoped " + uiScoped.getSimpleName() + " напрямую");
                    }
                }
            }
        }

        assertThat(inspected)
            .as("забор обязан просмотреть существенную часть контекста, а не один бин")
            .isGreaterThan(50);
        assertThat(violations)
            .as("синглтон внедряет UI-scoped бин напрямую. У @UIScope нет scoped-proxy: такой бин"
                + " резолвится один раз и остаётся привязанным к UI первого пользователя (или роняет"
                + " старт вне UI). Возьмите ObjectProvider/Provider или @Lazy на самой точке"
                + " внедрения и резолвите в момент вызова — как GlobalSearchNavigationAdapter."
                + " Класс-уровневый @Lazy здесь не помогает: он откладывает создание бина, а не"
                + " разрешение зависимости")
            .isEmpty();
    }

    private ConfigurableListableBeanFactory beanFactory() {
        return context.getBeanFactory();
    }

    private static List<String> definitionNamesOfType(ConfigurableListableBeanFactory factory,
                                                      Class<?> type) {
        List<String> names = new ArrayList<>();
        for (String name : factory.getBeanDefinitionNames()) {
            if (type.equals(resolveType(factory, factory.getBeanDefinition(name)))) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Класс бин-дефиниции — тремя способами, потому что ни один не работает для всех случаев.
     *
     * <p>У бинов, зарегистрированных {@code @Import}, пуст {@code getResolvableType()} (координатор
     * был таким до D3.5.5), поэтому имя класса разрешается вторым шагом. У бинов, объявленных
     * {@code @Bean}-методом, пусты <b>и</b> resolvable type, <b>и</b> имя класса: тип существует
     * только как возвращаемый тип фабричного метода — это третий шаг, добавленный D3.5.5.</p>
     *
     * <p>Момент, который стоит назвать явно: без третьего шага забор на новой схеме регистрации
     * становится <i>вакуумным</i> и молчит — именно так он и падал ("Expected size: 1 but was: 0")
     * при переходе координатора на {@code @Bean}. То есть сначала ломается сам измеритель, а не
     * измеряемое.</p>
     */
    private static Class<?> resolveType(ConfigurableListableBeanFactory factory,
                                        BeanDefinition definition) {
        Class<?> type = definition.getResolvableType().resolve();
        if (type != null) {
            return type;
        }
        String className = definition.getBeanClassName();
        if (className != null) {
            try {
                return org.springframework.util.ClassUtils.forName(className,
                    factory.getBeanClassLoader());
            } catch (ClassNotFoundException | LinkageError notOnClasspath) {
                return null;
            }
        }
        return factoryMethodReturnType(factory, definition);
    }

    /** Тип бина, объявленного {@code @Bean}-методом: только он и известен до создания бина. */
    private static Class<?> factoryMethodReturnType(ConfigurableListableBeanFactory factory,
                                                    BeanDefinition definition) {
        String factoryBeanName = definition.getFactoryBeanName();
        String factoryMethod = definition.getFactoryMethodName();
        if (factoryBeanName == null || factoryMethod == null) {
            return null;
        }
        Class<?> factoryType = factory.getType(factoryBeanName, false);
        for (Class<?> current = factoryType; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(factoryMethod)) {
                    return method.getReturnType();
                }
            }
        }
        return null;
    }

    /** Типы, чьи бины объявлены в UI-скоупе: именно их напрямую внедрять нельзя. */
    private static Set<Class<?>> uiScopedTypes(ConfigurableListableBeanFactory factory) {
        Set<Class<?>> types = new TreeSet<>(Comparator.comparing(Class::getName));
        for (String name : factory.getBeanDefinitionNames()) {
            BeanDefinition definition = factory.getBeanDefinition(name);
            if (!UI_SCOPE.equals(definition.getScope())) {
                continue;
            }
            Class<?> type = resolveType(factory, definition);
            if (type != null) {
                types.add(type);
            }
        }
        return types;
    }

    /** Точки внедрения бина: выбранный конструктор, помеченные поля и параметры @Bean-метода. */
    private static List<InjectionPoint> injectionPoints(ConfigurableListableBeanFactory factory,
                                                        BeanDefinition definition, Class<?> type) {
        List<InjectionPoint> points = new ArrayList<>();
        String factoryMethod = definition.getFactoryMethodName();
        if (factoryMethod != null) {
            Class<?> factoryType = definition.getFactoryBeanName() == null ? type
                : factory.getType(definition.getFactoryBeanName(), false);
            for (Class<?> current = factoryType; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    if (!method.getName().equals(factoryMethod)) {
                        continue;
                    }
                    for (Parameter parameter : method.getParameters()) {
                        points.add(new InjectionPoint(parameter.getType(),
                            "@Bean " + factoryMethod + "(… " + parameter.getName() + ")",
                            isLazy(parameter.getType(), parameter.isAnnotationPresent(Lazy.class))));
                    }
                }
                if (!points.isEmpty()) {
                    break;
                }
            }
            return points;
        }

        Constructor<?> chosen = null;
        for (Constructor<?> candidate : type.getDeclaredConstructors()) {
            if (candidate.isAnnotationPresent(Autowired.class)) {
                chosen = candidate;
                break;
            }
        }
        if (chosen == null && type.getDeclaredConstructors().length == 1) {
            chosen = type.getDeclaredConstructors()[0];
        }
        if (chosen != null) {
            for (Parameter parameter : chosen.getParameters()) {
                points.add(new InjectionPoint(parameter.getType(),
                    "ctor " + type.getSimpleName() + "(… " + parameter.getName() + ")",
                    isLazy(parameter.getType(), parameter.isAnnotationPresent(Lazy.class))));
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (field.isAnnotationPresent(Autowired.class)) {
                points.add(new InjectionPoint(field.getType(), "field " + field.getName(),
                    isLazy(field.getType(), field.isAnnotationPresent(Lazy.class))));
            }
        }
        return points;
    }

    private static boolean isLazy(Class<?> type, boolean annotatedLazy) {
        return annotatedLazy || isLazyWrapper(type);
    }

    private static boolean isLazyWrapper(Class<?> type) {
        return LAZY_WRAPPERS.contains(type.getName());
    }

    private static boolean isProjectClass(Class<?> type) {
        String name = type.getName();
        return name.startsWith("org.ip.") || name.startsWith("org.ipro.");
    }

    /** Одна точка внедрения: что именно внедряется и как. */
    private record InjectionPoint(Class<?> rawType, String description, boolean lazy) {
    }
}
