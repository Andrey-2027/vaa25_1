package org.ip.routes;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteData;
import com.vaadin.flow.server.RouteRegistry;
import com.vaadin.flow.server.VaadinServletContext;
import com.vaadin.flow.server.startup.ApplicationRouteRegistry;
import com.vaadin.flow.spring.annotation.EnableVaadin;
import jakarta.servlet.ServletContext;
import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ipro.report.ui.catalog.ReportCatalogView;
import org.ipro.unidr.ui.UniDrView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Реестр маршрутов живого приложения — единственный признак, отличающий «модуль подключён» от
 * «экран открывается».
 *
 * <p>Бины платформенного UI объявляет сам модуль ({@code ReportUiAutoConfiguration}), а
 * {@code @Route} Vaadin ищет только в объявленных корнях сканирования: значение
 * {@link EnableVaadin} заменяет (не дополняет) пакет {@code @SpringBootApplication}. Поэтому
 * переезд UI в артефакт оставляет все прочие гейты зелёными — {@code DeepLinkRouteBaselineTest}
 * читает исходники, контекстные тесты видят бины, — а {@code /report-catalog} и {@code /unidr}
 * отвечают как несуществующие адреса. В M2.2.2 так и вышло: пакет {@code org.ipro} в списке
 * корней приложения забыли, и разницу поймала только эта проверка (см.
 * {@code docs/architecture/evidence/m2-2-3-route-registration-20261027.md}).
 *
 * <p>Забор обобщённый, а не про два адреса: он берёт корни из самого {@link EnableVaadin} и
 * требует, чтобы каждый production-класс с {@code @Route} был в реестре. Иначе следующий
 * переезд (M2.2.3 и дальше) повторил бы ту же тихую поломку.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RouteRegistrationTest {

    /** Тот же известный дефект сидинга при свежей БД — вне скоупа (см. RlsAutoConfigurationSmokeTest). */
    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private ApplicationContext context;

    @Test
    void everyProductionRouteClassOfTheScanRootsIsRegistered() {
        Set<Class<?>> registered = registeredTargets();

        assertThat(scannedProductionRouteClasses())
            .as("@Route живёт в объявленном корне сканирования, иначе адрес отвечает как несуществующий")
            .isNotEmpty()
            .allSatisfy(routeClass -> assertThat(registered).contains(routeClass));
        // Корень приложения обязан остаться: значение @EnableVaadin заменяет пакет
        // @SpringBootApplication, а не дополняет его.
        assertThat(registered).as("прикладные экраны остаются адресуемыми")
            .contains(org.ip.views.login.LoginView.class);
    }

    @Test
    void reportUiOfPlatformArtifactIsReachableByPublishedPaths() {
        // Четыре адреса deep-link-baseline: владелец — platform-report-vaadin, то есть UI
        // приезжает артефактом, а маршруты должны быть зарегистрированы в приложении.
        assertThat(registry().getNavigationTarget("report-catalog")).contains(ReportCatalogView.class);
        assertThat(registry().getNavigationTarget("report-catalog-compact")).contains(ReportCatalogView.class);
        assertThat(registry().getNavigationTarget("report-catalog-structured")).contains(ReportCatalogView.class);
        assertThat(registry().getNavigationTarget("unidr")).contains(UniDrView.class);
    }

    /** Цели, реально лежащие в реестре: адрес выводится Vaadin, а не наоборот. */
    private Set<Class<?>> registeredTargets() {
        Set<Class<?>> targets = new LinkedHashSet<>();
        for (RouteData data : registry().getRegisteredRoutes()) {
            targets.add(data.getNavigationTarget());
        }
        return targets;
    }

    /**
     * Production-классы с {@code @Route} из корней {@link EnableVaadin}. Тестовый classpath
     * содержит demo-экраны ({@code org.ip.groupgrid}), они контрактом приложения не являются,
     * поэтому классы из {@code test-classes} отбрасываются по месту ресурса.
     */
    private Set<Class<?>> scannedProductionRouteClasses() {
        EnableVaadin root = AnnotatedElementUtils.findMergedAnnotation(Application.class, EnableVaadin.class);
        assertThat(root).as("Application объявляет корни сканирования Vaadin").isNotNull();

        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Route.class));
        Set<Class<?>> found = new LinkedHashSet<>();
        ClassLoader classLoader = getClass().getClassLoader();
        for (String basePackage : root.value()) {
            for (var definition : scanner.findCandidateComponents(basePackage)) {
                Class<?> type = resolve(definition.getBeanClassName(), classLoader);
                if (type != null && isRouteTargetContract(type)) {
                    found.add(type);
                }
            }
        }
        return found;
    }

    private static Class<?> resolve(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException notOnClasspath) {
            return null;
        }
    }

    private static boolean isRouteTargetContract(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers()) || Modifier.isAbstract(type.getModifiers())) {
            return false;
        }
        if (!Component.class.isAssignableFrom(type)) {
            return false;
        }
        URL location = type.getResource("/" + type.getName().replace('.', '/') + ".class");
        return location == null || !location.toString().contains("/test-classes/");
    }

    /**
     * Реестр читается из того же места, куда его кладёт старт Vaadin: атрибут сервлет-контекста.
     * Бина {@code RouteRegistry} в контексте Spring нет, а {@code VaadinService.getCurrent()}
     * в тестовом потоке пуст — поэтому не инъекция, а тот же {@code ApplicationRouteRegistry},
     * которым пользуется {@code VaadinServletContextInitializer}.
     */
    private RouteRegistry registry() {
        ServletContext servletContext =
            ((ServletWebServerApplicationContext) context).getServletContext();
        return ApplicationRouteRegistry.getInstance(new VaadinServletContext(servletContext));
    }
}
