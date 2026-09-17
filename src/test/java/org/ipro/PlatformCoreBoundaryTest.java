package org.ipro;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3.2 — границы, без которых вынос {@code platform-core} невозможен.
 *
 * <p>План D3 начинается не с переноса классов, а с разрыва core/UI-циклов: переносить
 * {@code platform-core} можно только тогда, когда множество его классов замкнуто без Vaadin.
 * До этой работы замыкание не выполнялось ровно в трёх местах, и каждое было не «грязью», а
 * одной строкой wiring'а:</p>
 *
 * <ol>
 * <li>{@code MetadataAutoConfiguration} создавала {@code MetadataDrivenItemFormSaveAdapter} —
 *     ядро метаданных импортировало {@code org.ipro.form} ради одного бина;</li>
 * <li>{@code GlobalSearchAutoConfiguration} создавала {@code GlobalSearchHeader} (Vaadin) и
 *     {@code GlobalSearchNavigationAdapter} (нужен {@code FormCoordinator}) — ядро поиска
 *     знало о UI;</li>
 * <li>{@code org.ipro.metadata.explorer} — read-model explorer'а — импортировал
 *     {@code form.registry.FormRegistry} и {@code form.builder.ContextFilterField}.</li>
 * </ol>
 *
 * <p>Первые два исправлены переносом владения бином в формовый слой, третий — переносом
 * read-model в UI-границу ({@code org.ipro.vaadin.explorer}), потому что модель, которая
 * наполняется из {@code FormRegistry}, принадлежит UI-слою, а не метаданным.</p>
 *
 * <p><b>Почему это отдельные правила.</b> {@code org.ipro.metadata -> org.ipro.form} был
 * записан в {@code docs/architecture/d1-platform-boundary-map.md} как осознанное исключение
 * («explorer переезжает вместе с form-срезом»). Здесь исключение закрыто: правило
 * {@link #metadataCoreDoesNotDependOnFormLayer} зеленеет только потому, что переезд
 * действительно произошёл. Правило, написанное «на будущее», ничего не доказывает; правило,
 * написанное после закрытия исключения, держит закрытие.</p>
 *
 * <p><b>Замечание о classpath.</b> {@code org.ipro.crud} содержит и классы этого дерева, и
 * классы внешнего артефакта {@code crudui-core}, который зависит от Vaadin по определению, и
 * то же верно для {@code filtergrid}. Поэтому анализ идёт с их исключением — правило проверяет
 * платформу (дерево плюс артефакты {@code platform-*}), а не чужую UI-библиотеку.</p>
 *
 * <p><b>Почему без {@code DoNotIncludeJars}.</b> Пока {@code org.ipro.crud} и
 * {@code org.ipro.metadata} лежали в дереве, {@code DoNotIncludeJars} давал точную картину.
 * После D3.3 те же классы переехали в {@code platform-core}, и та же настройка превратила
 * правила в проверку пяти оставшихся {@code org.ipro.*.config} — то есть гейт продолжал
 * заявлять про всё ядро, а смотрел на конфигурации. Это ровно тот вид дефекта, который в этих
 * этапах ловится третий раз: зелёный гейт, потерявший предмет. Поэтому граница теперь
 * проверяется и на уровне байткода артефактов, а {@link #analyzedCoreClassesComeFromTheArtifact}
 * запрещает ситуации, когда правило снова окажется наблюдать нечего.</p>
 */
@AnalyzeClasses(packages = "org.ipro",
    importOptions = {ImportOption.DoNotIncludeTests.class,
        PlatformCoreBoundaryTest.PlatformAndApplicationLocationsOnly.class})
class PlatformCoreBoundaryTest {

    /**
     * Классы, попадающие в анализ: дерево этого чекаута и артефакты {@code platform-*}. Чужие
     * UI-библиотеки ({@code crudui-core}, {@code filtergrid}) исключаются по пути к классу —
     * они зависят от Vaadin по определению и к платформе не относятся.
     */
    static class PlatformAndApplicationLocationsOnly implements ImportOption {

        @Override
        public boolean includes(Location location) {
            String path = location.asURI().toString().toLowerCase(java.util.Locale.ROOT);
            return !path.contains("/crudui/") && !path.contains("crudui-core")
                && !path.contains("filtergrid");
        }
    }

    /** Пакеты будущего {@code platform-core}: backend платформы, без Vaadin и без кода приложения. */
    private static final String[] CORE_CANDIDATES = {
        "org.ipro.crud..",
        "org.ipro.data..",
        "org.ipro.fetch..",
        "org.ipro.filter..",
        "org.ipro.metadata..",
        "org.ipro.search..",
        "org.ipro.security..",
    };

    /**
     * {@code core} в плане D3 означает «без Vaadin и без кода приложения», а не «чистый Java»:
     * Spring и JPA здесь допустимы. Vaadin — нет: иначе артефакт нельзя собрать без UI и
     * подключить к не-UI потребителю.
     */
    @ArchTest
    static final ArchRule coreCandidatesDoNotDependOnVaadin =
            noClasses().that().resideInAnyPackage(CORE_CANDIDATES)
                    .should().dependOnClassesThat().resideInAnyPackage("com.vaadin..")
                    .because("platform-core — backend: Vaadin живёт в platform-vaadin,"
                            + " а не в ядре платформы");

    /**
     * Платформа не знает приложения. Это то же правило, что в {@link PlatformArchitectureTest},
     * но применённое к каждому core-кандидату по отдельности: при выносе модуля нарушение
     * должно ломаться в модуле-владельце, а не «где-то в платформе».
     */
    @ArchTest
    static final ArchRule coreCandidatesDoNotDependOnApplicationCode =
            noClasses().that().resideInAnyPackage(CORE_CANDIDATES)
                    .should().dependOnClassesThat().resideInAnyPackage("org.ip..")
                    .because("приложение зависит от платформы; обратная ссылка делает вынос"
                            + " модуля невозможным без копии прикладного класса");

    /**
     * Закрытие исключения, записанного в D1-карте: metadata-ядро публикует API, события и
     * модель метаданных, но не read-model форм. Explorer переехал в
     * {@code org.ipro.vaadin.explorer}, поэтому правило теперь можно держать зелёным.
     */
    @ArchTest
    static final ArchRule metadataCoreDoesNotDependOnFormLayer =
            noClasses().that().resideInAnyPackage("org.ipro.metadata..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.ipro.form..")
                    .because("metadata-ядро не знает о формах: form-зависимый read-model"
                            + " (explorer) принадлежит UI-границе org.ipro.vaadin.explorer");

    /**
     * Search-ядро — модель запроса, каталог, провайдеры, сервис — не знает ни форм, ни формовой
     * навигации. Шапка поиска и переход к карточке переехали в {@code org.ipro.vaadin.search}.
     */
    @ArchTest
    static final ArchRule searchCoreDoesNotDependOnFormLayer =
            noClasses().that().resideInAnyPackage("org.ipro.search..")
                    .should().dependOnClassesThat().resideInAnyPackage("org.ipro.form..")
                    .because("поиск — read-механика: навигация к карточке — забота UI-границы,"
                            + " поэтому ядро поиска не зависит от FormCoordinator");

    /**
     * Проверка не должна стать вакуумной: классы ядра обязаны приходить из артефакта.
     *
     * <p>Это прямой урок предыдущей версии гейта. {@code DoNotIncludeJars} был корректен, пока
     * ядро жило в дереве; после выноса он оставил правилам пять {@code config}-классов вместо 92
     * типов — и гейт зеленел бы, даже если бы весь {@code platform-core} начал зависеть от
     * Vaadin. Тест утверждает не «правило зелёное», а что у правила есть предмет.</p>
     */
    @Test
    void analyzedCoreClassesComeFromTheArtifact() {
        var classes = new ClassFileImporter()
            .withImportOption(new PlatformAndApplicationLocationsOnly())
            .importPackages("org.ipro");

        List<String> coreTypes = classes.stream()
            .map(javaClass -> javaClass.getName())
            .filter(name -> name.startsWith("org.ipro.crud.") || name.startsWith("org.ipro.data.")
                || name.startsWith("org.ipro.metadata.") || name.startsWith("org.ipro.search.")
                || name.startsWith("org.ipro.fetch."))
            .toList();

        assertThat(coreTypes)
            .as("если ядро снова окажется вне анализа, правило о Vaadin перестанет что-либо"
                + " проверять и останется зелёным — поэтому предмет проверяется явно")
            .hasSizeGreaterThan(50);

        List<String> locations = new ArrayList<>();
        for (var javaClass : classes) {
            if (javaClass.getName().startsWith("org.ipro.metadata.MetadataResolver")) {
                locations.add(javaClass.getSource().orElseThrow().getUri().toString());
            }
        }
        assertThat(locations)
            .as("класс ядра обязан грузиться из platform-core, а не из дерева")
            .isNotEmpty()
            .allMatch(location -> location.contains("platform-core"));
    }

    /**
     * Исходники платформенных артефактов не называют классы приложения.
     *
     * <p>Проверка по файлам, а не через ArchUnit, и это не дублирование: модули
     * {@code platform-*} собираются отдельными Maven-проектами и попадают в этот анализ как
     * jar'ы, которые {@code DoNotIncludeJars} исключает. Без этой проверки нарушение направления
     * обнаруживалось бы только при сборке модуля — то есть уже после того, как оно попадёт в
     * репозиторий. Сейчас нарушений ноль, и проверка фиксирует именно это.</p>
     */
    @Test
    void platformModuleSourcesDoNotReferenceTheApplication() {
        Pattern applicationImport = Pattern.compile("^import\\s+(org\\.ip\\.[A-Za-z0-9_.]*);",
            Pattern.MULTILINE);
        List<String> violations = new ArrayList<>();

        try (Stream<Path> modules = Files.list(Path.of("."))) {
            List<Path> platformModules = modules
                .filter(path -> path.getFileName().toString().startsWith("platform-"))
                .filter(Files::isDirectory)
                .sorted()
                .toList();

            assertThat(platformModules)
                .as("проверка не должна быть вакуумной: модули платформы обязаны находиться")
                .isNotEmpty();

            for (Path module : platformModules) {
                for (Path source : javaSources(module.resolve("src/main/java"))) {
                    var matcher = applicationImport.matcher(read(source));
                    while (matcher.find()) {
                        violations.add(module.getFileName() + ": "
                            + source.getFileName() + " → " + matcher.group(1));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(violations)
            .as("платформенный модуль не может зависеть от приложения: иначе он перестаёт быть"
                + " модулем и становится частью этого конкретного приложения")
            .isEmpty();
    }

    private static List<Path> javaSources(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
