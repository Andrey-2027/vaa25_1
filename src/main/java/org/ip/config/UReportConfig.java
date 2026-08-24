package org.ip.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import com.bstek.ureport.console.UReportServlet;
import com.bstek.ureport.definition.datasource.BuildinDatasource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;

import javax.sql.DataSource;

/**
 * Интеграция UReport3 (веб-дизайнер отчётов).
 *
 * Бины движка берутся из XML-контекста форка (ureport-console-context.xml -> core/font),
 * значения плейсхолдеров (${ureport.*}) резолвятся из application.properties:
 * Environment Boot'а имеет приоритет над classpath:ureport.properties внутри форка.
 *
 * Доступ к /ureport/** защищается VaadinSecurityConfigurer по умолчанию
 * (всё, что не Vaadin-URL, требует аутентификации).
 */
@Configuration
@ImportResource("classpath:ureport-console-context.xml")
public class UReportConfig {

    public static final String URL_MAPPING = "/ureport/*";

    @Bean
    public ServletRegistrationBean<UReportServlet> ureportServlet() {
        ServletRegistrationBean<UReportServlet> reg =
                new ServletRegistrationBean<>(new UReportServlet(), URL_MAPPING);
        // fail-fast: если корневой контекст недоступен сервлету — узнаем на старте,
        // а не при первом открытии дизайнера
        reg.setLoadOnStartup(1);
        reg.setName("ureportServlet");
        return reg;
    }

    /**
     * Встроенный источник данных дизайнера на рабочей БД приложения.
     * Появляется в дизайнере: Источник данных -> Добавить встроенный источник.
     * Пользователь выбирает его по имени и пишет SQL — без ввода креденшелов
     * (подключение берётся из пула приложения, включая RLS-настройки при необходимости).
     */
    @Bean
    public BuildinDatasource workingDbDatasource(DataSource dataSource) {
        return new BuildinDatasource() {
            @Override
            public String name() {
                return "Рабочая база";
            }

            @Override
            public java.sql.Connection getConnection() {
                try {
                    return dataSource.getConnection();
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException("Не удалось получить соединение рабочей БД для отчёта", e);
                }
            }
        };
    }

    /**
     * Каталог шаблонов должен существовать до первого запроса дизайнера:
     * FileReportProvider.getReportFiles() падает с NPE на отсутствующем каталоге.
     */
    @Bean
    public ApplicationRunner ureportStorageInitializer(
            @Value("${ureport.fileStoreDir}") String fileStoreDir) {
        return args -> {
            try {
                Files.createDirectories(Paths.get(fileStoreDir));
            } catch (IOException e) {
                throw new IllegalStateException(
                        "Не удалось создать каталог хранения шаблонов UReport: " + fileStoreDir, e);
            }
        };
    }
}
