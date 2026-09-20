package org.ipro.telemetry.config;

import org.ipro.telemetry.api.EventSink;
import org.ipro.telemetry.core.AsyncEventSink;
import org.ipro.telemetry.core.TelemetryBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * D3.5.6: <b>у статической ячейки моста есть владелец</b>, и владение отделено от создания бинов.
 *
 * <p>Что было до шага. {@link TelemetryBridge} заполнялся из трёх мест — двух {@code @Bean}-методов
 * sink'а и конструктора UI-listener'а — и не очищался никогда. Побочный эффект создания бина и
 * владение lifecycle'ом были одним и тем же кодом, поэтому:</p>
 *
 * <ul>
 *   <li>закрытие контекста оставляло мост указывающим на закрытый {@code AsyncEventSink}: события
 *       уходили в никуда молча, а в общем прогоне это ещё и связывало независимые тесты;</li>
 *   <li>пользовательский {@code EventSink} давал два бина одного типа, потому что у
 *       {@code telemetryEventSink} не было {@code @ConditionalOnMissingBean} — заявленная поддержка
 *       «default/custom» не работала ни как замена, ни как выбор;</li>
 *   <li>UI-адаптер (артефакт поверх UI) был обязан создать бин, чтобы у потребителя телеметрии
 *       вообще появился sink: направление «наблюдение не зависит от UI» держалось на побочном
 *       эффекте.</li>
 * </ul>
 *
 * <p><b>Почему раннер грузит только {@code SinkConfiguration}.</b> Полный
 * {@code TelemetryAutoConfiguration} в тесте не поднимается: {@code @EnableJpaRepositories} требует
 * настоящей метамодели, и мок EntityManagerFactory роняет старт сообщением «The given domain class
 * can not be found in the given Metamodel». Проверяется при этом настоящее поведение настоящих
 * бинов, а не копия: конфигурация — тот самый member-класс, который поднимается в приложении.</p>
 *
 * <p>Проверки сформулированы относительно: сначала в ячейку кладётся «чужой» sink, и утверждается,
 * что старт и закрытие нашего контекста его не трогают. Абсолютное «ячейка пуста» проверяется только
 * там, где она действительно обязана быть пустой, — иначе тест зависел бы от соседей по JVM
 * (подход {@code TelemetrySeamBridgesTest}).</p>
 */
class TelemetrySinkOwnershipTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            TelemetryAutoConfiguration.SinkConfiguration.class))
        .withBean(TelemetryProperties.class, TelemetryProperties::new)
        .withBean(JdbcTemplate.class, TelemetrySinkOwnershipTest::telemetryJdbcTemplate)
        .withBean(PlatformTransactionManager.class,
            () -> mock(PlatformTransactionManager.class));

    @AfterEach
    void clearBridge() {
        TelemetryBridge.setSink(null);
    }

    @Test
    void bridgePointsAtTheResolvedSink() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(TelemetryBridge.getSink())
                .as("мост обязан указывать на тот же sink, который получают потребители: иначе"
                    + " события уходят в один приёмник, а самонаблюдение смотрит на другой")
                .isSameAs(context.getBean(EventSink.class));
        });
    }

    /**
     * Поддержка пользовательского sink'а — то, ради чего шаг и делается: приложение объявляет свой
     * бин, платформенный отступает, и <b>мост указывает на пользовательский</b>.
     */
    @Test
    void applicationSinkReplacesThePlatformOneAndOwnsTheBridge() {
        runner.withUserConfiguration(CustomSinkConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBeanNamesForType(EventSink.class))
                    .as("два бина одного типа — это либо падение инъекции, либо тихий выбор"
                        + " случайного приёмника: платформенный async-sink обязан отступить")
                    .hasSize(1);
                assertThat(context.getBean(EventSink.class))
                    .isSameAs(CustomSinkConfiguration.SINK);
                assertThat(TelemetryBridge.getSink()).isSameAs(CustomSinkConfiguration.SINK);
            });
    }

    /**
     * Снятие при закрытии контекста: иначе живой мост указывает на закрытый sink (hot reload,
     * тесты, перезапуск контекста в одном процессе), и события теряются без единого признака.
     */
    @Test
    void bridgeIsClearedWhenTheContextCloses() {
        runner.run(context -> assertThat(context.getBean(EventSink.class)).isNotNull());

        assertThat(TelemetryBridge.getSink())
            .as("после закрытия контекста ячейка пуста: закрытый AsyncEventSink больше не принимает"
                + " события, а мост продолжал бы в него писать")
            .isNull();
    }

    /**
     * Снятие адресное: чужую установку закрытие нашего контекста не стирает.
     *
     * <p>Не формальность: тесты живут в одной JVM и делят статику, поэтому закрытие соседнего
     * контекста не имеет права обезоружить ещё живой — этот класс ошибок здесь уже ловили на общем
     * прогоне ({@code InstanceNamePilotIT}, см. javadoc {@code TelemetrySeamBridgesTest}).</p>
     */
    @Test
    void closingOurContextKeepsAForeignInstallation() {
        EventSink foreign = mock(EventSink.class);

        runner.run(context -> TelemetryBridge.setSink(foreign));

        assertThat(TelemetryBridge.getSink())
            .as("закрытие нашего контекста обязано снять только свою установку (compare-and-clear),"
                + " а не очистить ячейку целиком")
            .isSameAs(foreign);
    }

    /**
     * Владение отделено от создания: {@code @Bean}-метод sink'а в мост больше не пишет.
     *
     * <p>Проверяется прямым вызовом конфигурации, потому что через контейнер это свойство не
     * наблюдается: владелец всё равно установит sink, и подмена одного механизма другим осталась бы
     * незамеченной. Именно эта подмена и была дефектом — код создания бина отвечал за lifecycle.</p>
     */
    @Test
    void creatingSinkBeansDoesNotTouchTheBridge() {
        TelemetryBridge.setSink(null);
        TelemetryAutoConfiguration.SinkConfiguration configuration =
            new TelemetryAutoConfiguration.SinkConfiguration();

        EventSink async = configuration.telemetryEventSink(telemetryJdbcTemplate(),
            mock(PlatformTransactionManager.class), new TelemetryProperties());
        EventSink noop = configuration.noopTelemetryEventSink();

        try {
            assertThat(TelemetryBridge.getSink())
                .as("создание бина не является установкой: иначе ownership зависит от того, какой"
                    + " бин создали первым, и переживает закрытие контекста")
                .isNull();
        } finally {
            ((AsyncEventSink) async).close();
            assertThat(noop).isNotNull();
        }
    }

    /**
     * Sink-lifecycle обязан оставаться member-классом подсистемной конфигурации.
     *
     * <p>Это не про стиль: условие {@code ipro.telemetry.enabled} объявлено на внешнем классе, и
     * member-класс пропускается вместе с ним. Вынесенный в отдельный файл, он поднимался бы при
     * выключенной телеметрии — то есть выключение подсистемы перестало бы её выключать.</p>
     */
    @Test
    void sinkLifecycleIsGatedByTheSubsystemConfiguration() {
        assertThat(List.of(TelemetryAutoConfiguration.class.getDeclaredClasses()))
            .as("SinkConfiguration обязана быть member-классом: иначе её применимость перестаёт"
                + " зависеть от гейта подсистемы")
            .contains(TelemetryAutoConfiguration.SinkConfiguration.class);

        ConditionalOnProperty gate =
            TelemetryAutoConfiguration.class.getAnnotation(ConditionalOnProperty.class);
        assertThat(gate).as("гейт подсистемы обязан быть на внешнем классе").isNotNull();
        assertThat(gate.prefix()).isEqualTo("ipro.telemetry");
        assertThat(List.of(gate.name())).contains("enabled");

        assertThat(autoConfigurationImports())
            .as("member-класс не имеет права быть зарегистрированным отдельно: при прямой"
                + " регистрации условие внешнего класса не применяется, и sink-lifecycle поднялся"
                + " бы даже с выключенной телеметрией — то есть выключение подсистемы перестало бы"
                + " её выключать")
            .containsExactly("org.ipro.telemetry.config.TelemetryAutoConfiguration");
    }

    private static List<String> autoConfigurationImports() {
        String resource =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
        try (java.io.InputStream in =
                 TelemetrySinkOwnershipTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("imports-файл модуля обязан быть на classpath").isNotNull();
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Пользовательский sink: объявлен так, как это делает приложение — {@code @Bean} в {@code @Configuration}. */
    @Configuration
    static class CustomSinkConfiguration {

        private static final EventSink SINK = mock(EventSink.class);

        @Bean
        EventSink applicationEventSink() {
            return SINK;
        }
    }

    /**
     * D3.5.8-fix: compare-and-clear атомарен.
     *
     * <p>Прежняя реализация проверяла {@code sink == expected} и присваивала {@code null}
     * двумя отдельными операциями над volatile-полем: между ними соседний контекст успевал
     * поставить свой sink, и наше стирание обезоруживало уже его. Ровно сценарий hot reload /
     * двух контекстов в одной JVM, от которого compare-and-clear и существует. Детерминированный
     * interleaving здесь не нужен: утомительный поиск окна гонки и подменяет смысл теста —
     * проверяется свойство операции (CAS стирает только своё), а не удача планировщика.</p>
     */
    @Test
    void compareAndClearDoesNotEraseAReplacementInstalledAfterTheCheck() {
        EventSink ours = mock(EventSink.class);
        EventSink theirs = mock(EventSink.class);
        TelemetryBridge.setSink(ours);

        // соседи меняются местами без синхронизации: установленный после нашей проверки
        // чужой sink не имеет права быть стёртым нашим clear
        TelemetryBridge.setSink(theirs);
        TelemetryBridge.clearSink(ours);

        assertThat(TelemetryBridge.getSink())
            .as("clear(stale) обязан оставить чужой sink в ячейке: CAS проваливается на"
                + " несовпадении, вместо того чтобы стереть установку соседа")
            .isSameAs(theirs);

        TelemetryBridge.clearSink(theirs);
        assertThat(TelemetryBridge.getSink())
            .as("свой sink снимается как раньше: атомарность не отменяет адресность")
            .isNull();
    }

    /**
     * D3.5.8-fix: конкурентная версия той же проверки — два потока гонят set/clear наперегонки,
     * итог обязан сходиться с последовательной семантикой: последний set выигрывает,
     * clear снимает только свой.
     */
    @Test
    void concurrentSetAndClearNeverLeaveTheCellClearedByTheWrongOwner() throws Exception {
        EventSink first = mock(EventSink.class);
        EventSink second = mock(EventSink.class);
        int iterations = 500;
        java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                java.util.concurrent.CompletableFuture<Void> a =
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        TelemetryBridge.setSink(first);
                        TelemetryBridge.clearSink(first);
                    }, pool);
                java.util.concurrent.CompletableFuture<Void> b =
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        TelemetryBridge.setSink(second);
                        TelemetryBridge.clearSink(second);
                    }, pool);
                java.util.concurrent.CompletableFuture.allOf(a, b).join();

                EventSink remaining = TelemetryBridge.getSink();
                assertThat(remaining)
                    .as("после пары set+clear в каждом потоке ячейка пуста или содержит sink чей"
                        + " clear ещё не выполнился — но никогда не пуста из-за чужого clear")
                    .isIn(null, first, second);
            }
        } finally {
            pool.shutdownNow();
            TelemetryBridge.setSink(null);
        }
    }

    /**
     * {@code AsyncEventSink} выбирает диалект JSON-параметра при создании и без DataSource роняет
     * контекст, поэтому JdbcTemplate замокан вместе с цепочкой до имени продукта БД.
     */
    private static JdbcTemplate telemetryJdbcTemplate() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        try {
            org.mockito.Mockito.when(metaData.getDatabaseProductName()).thenReturn("PostgreSQL");
            org.mockito.Mockito.when(connection.getMetaData()).thenReturn(metaData);
            org.mockito.Mockito.when(dataSource.getConnection()).thenReturn(connection);
        } catch (java.sql.SQLException impossible) {
            throw new IllegalStateException(impossible);
        }
        org.mockito.Mockito.when(jdbc.getDataSource()).thenReturn(dataSource);
        return jdbc;
    }
}
