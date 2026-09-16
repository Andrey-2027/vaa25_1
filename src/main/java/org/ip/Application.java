package org.ip;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.Theme;
import com.vaadin.flow.theme.lumo.Lumo;
import org.ip.config.AuditConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Точка входа платформы.
 *
 * <p>{@link AuditConfig} подключается через {@code @Import}, а не только
 * component scan: аудит — часть границы маппинга {@link org.ipro.crud.BaseEntity}
 * ({@code @CreatedDate}/{@code @CreatedBy}), поэтому его инфраструктура должна быть в
 * КАЖДОМ контексте, который маппит эту сущность — включая slice-контексты
 * тестов, из которых component scan вырезает {@code org.ip.config}.
 *
 * <p>Без этого {@code AuditingEntityListener} (он на {@code BaseEntity} безусловно)
 * остаётся без своей инфраструктуры в контексте и настраивается
 * JVM-глобальным AspectJ-аспектом
 * {@code AnnotationBeanConfigurerAspect} — то есть чужим контекстом. Когда тот
 * закрывается, запись падает с {@code IllegalStateException: … has been closed already},
 * а пока он жив — аудит пишется «по доверенности» из чужого контекста.
 */
@SpringBootApplication
@Theme("default")
@StyleSheet(Lumo.UTILITY_STYLESHEET)
// D2 (persistence slice): пакеты артефакта platform-persistence здесь больше не перечисляются —
// он объявляет свои @EntityScan/@EnableJpaRepositories сам, поэтому забыть о нём молча нельзя.
// D2 → D3: то же для platform-numbering, platform-settings и platform-telemetry —
// вынесенные модули владеют своими пакетами сами.
// D2 → D3 (шаг 8б): reportstudio и ureport объявляют свои persistence-пакеты сами
// (ReportStudioPersistenceAutoConfiguration, UreportPersistenceAutoConfiguration) —
// остался только прикладной пакет.
@EntityScan({"org.ip.model"})
// D1: прикладной пакет объявляет свои репозитории сам. Платформенный
// RlsAutoConfiguration перечисляет только платформенные пакеты, поэтому имя org.ip
// больше не встречается в строковых контрактах платформы.
@EnableJpaRepositories({"org.ip"})
@EnableTransactionManagement(proxyTargetClass = true, order = 0)
@Import(AuditConfig.class)
public class Application implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
