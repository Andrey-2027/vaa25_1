package org.ipro;

import org.ip.Application;
import org.ip.config.DataInitializer;
import org.ipro.form.registry.FormRegistry;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.ipro.vaadin.search.GlobalSearchNavigationAdapter;
import org.ipro.vaadin.telemetry.TelemetryVaadinInitListener;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3.5.4: подсистема UI собирается в <b>живом</b> контексте приложения после переезда.
 *
 * <p>Момент, ради которого этот тест существует. До переноса три конфигурации UI-слоя
 * перечислял imports-файл приложения, а telemetry-адаптер не был зарегистрирован нигде —
 * он находился в component scan'е пакета {@code org.ip} и «работал» именно поэтому. После
 * переноса в {@code platform-vaadin} способ регистрации сменился у всех четырёх, и ошибка
 * здесь не выглядит ошибкой: конфигурация уезжает из артефакта, бин не появляется, приложение
 * стартует, тесты компилируются, часть тестов остаётся зелёной. Пропадает только поведение —
 * в случае telemetry-адаптера тихо перестают попадать в журнал ошибки UI-сессий.</p>
 *
 * <p>Поэтому проверяются не файлы и не объявления (это делают
 * {@code PlatformVaadinModuleTest} и {@code PlatformAutoConfigurationRegistryTest}), а факт:
 * каждый шов подсистемы <b>имеет бин в контексте</b> и приходит из артефакта. Формовая часть
 * уже покрыта {@code DialogWorkspaceResolutionIT}, но проверять все четыре конфигурации в одном
 * месте дешевле, чем объяснять, почему одна из них осталась непроверенной.</p>
 *
 * <p>Координатор здесь сознательно не запрашивается: он {@code @UIScope}, и его область
 * проверяет {@code FormCoordinatorScopeGuardTest} — там же, где описано, почему
 * {@code getBean} вне UI-контекста был бы неверной проверкой.</p>
 */
/**
 * {@code classes = Application.class} указан явно: этот тест живёт в пакете {@code org.ipro}
 * (рядом с остальными заборами потребителя платформы), а точка входа приложения —
 * {@code org.ip.Application}, которую поиск «вверх по пакетам» не найдёт.
 */
@SpringBootTest(classes = Application.class)
class PlatformVaadinWiringIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private ApplicationContext context;

    /** Пары «шов подсистемы → его конфигурация»: имя нужно для понятного сообщения об ошибке. */
    private static final List<Seam> SEAMS = List.of(
        new Seam(FormRegistry.class, "org.ipro.form.config.FormAutoConfiguration"),
        new Seam(EntitySummaryAssembler.class,
            "org.ipro.vaadin.explorer.config.EntityExplorerAutoConfiguration"),
        new Seam(GlobalSearchNavigationAdapter.class,
            "org.ipro.vaadin.search.config.GlobalSearchVaadinAutoConfiguration"),
        new Seam(TelemetryVaadinInitListener.class,
            "org.ipro.vaadin.telemetry.TelemetryVaadinConfiguration"));

    @Test
    void everyUiSeamHasABeanInTheApplicationContext() {
        for (Seam seam : SEAMS) {
            assertThat(context.getBeanNamesForType(seam.type()))
                .as("%s не дал бин: подсистема подключена только объявлением, а не работой"
                    + " (приложение не сканирует org.ipro — регистрация идёт imports-файлом"
                    + " модуля)", seam.configuration())
                .hasSize(1);
        }
    }

    @Test
    void wiredSeamsComeFromTheModuleArtifact() {
        for (Seam seam : SEAMS) {
            var codeSource = seam.type().getProtectionDomain().getCodeSource();
            assertThat(codeSource)
                .as("%s должен быть доступен приложению", seam.type().getName())
                .isNotNull();
            assertThat(codeSource.getLocation().toString())
                .as("%s: класс обязан приходить из platform-vaadin, а не из дерева приложения"
                    + " — иначе перенос остался только в исходниках", seam.type().getName())
                .contains("platform-vaadin");
        }
    }

    private record Seam(Class<?> type, String configuration) {
    }
}
