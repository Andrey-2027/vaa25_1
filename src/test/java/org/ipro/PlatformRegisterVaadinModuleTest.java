package org.ipro;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-R S4 — что срез оставил на стороне потребителя: стандартный интерфейс регистра уехал
 * в {@code platform-register-vaadin}, приложение перестало быть его владельцем, но продолжает
 * им пользоваться.
 *
 * <p>Проверяются пять свойств, и все они — про отношение потребителя к артефакту:</p>
 * <ol>
 * <li><b>В дереве приложения не осталось ни копии, ни пакета.</b> Каталог
 *     {@code src/main/java/org/ip/views/register} после S4 пуст, а перенесённые классы приходят
 *     из артефакта, а не из {@code target/classes}: стейл прошлой сборки даёт точно такой же
 *     зелёный результат по файлам, поэтому адрес байткода проверяется отдельно.</li>
 * <li><b>Регистрацию держит модуль.</b> Приложение не сканирует {@code org.ipro}, поэтому без
 *     записи в imports-файле артефакта стандартный интерфейс исчез бы молча — гейт смотрит, что
 *     владелец записи — артефакт, а не приложение.</li>
 * <li><b>Приложение берёт интерфейс через стартер.</b> Прямой зависимости на
 *     {@code platform-register-vaadin} в pom приложения нет: поставка UI-платформы — стартер, и
 *     это то же правило, что уже действует для {@code platform-vaadin}.</li>
 * <li><b>Приложение не дотягивается внутрь.</b> Ни один production-класс приложения не называет
 *     типы модуля: единственная опора — тесты, и она названа явно (обратная сторона проверки
 *     «потребительские тесты компилируются против артефакта»).</li>
 * <li><b>Тесты приложения не живут в пакете модуля.</b> Test-side split package делает
 *     package-private тип модуля доступным через границу артефакта — и невидимым для заборов
 *     (импорта нет, тип в том же пакете). Именно поэтому тест диалога переехал в модуль
 *     с модульными фикстурами, а не остался в приложении.</li>
 * </ol>
 */
class PlatformRegisterVaadinModuleTest {

    private static final Path MODULE = Path.of("platform-register-vaadin");

    private static final Path APPLICATION_MAIN_SOURCES = Path.of("src/main/java");

    /** Каталог стандартного интерфейса в приложении: после S4 он обязан быть пустым. */
    private static final Path APPLICATION_INTERFACE = Path.of("src/main/java/org/ip/views/register");

    private static final Path APPLICATION_TEST_SOURCES = Path.of("src/test/java");

    private static final String APPLICATION_IMPORTS =
        "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private static final String MODULE_IMPORTS =
        "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private static final String CONFIGURATION = "org.ipro.vaadin.register.config.RegisterVaadinConfiguration";

    /** Перенесённые типы: класс обязан быть доступен приложению — и именно из артефакта. */
    private static final List<Class<?>> MOVED_TYPES = List.of(
        org.ipro.vaadin.register.RegisterFormCustomization.class,
        org.ipro.vaadin.register.RecorderRefPresentationResolver.class,
        org.ipro.vaadin.register.RecorderRefPresentation.class,
        org.ipro.vaadin.register.RecorderRefDisplay.class);

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private static final Pattern MODULE_IMPORT = Pattern.compile(
        "^\\s*import\\s+(?:static\\s+)?org\\.ipro\\.vaadin\\.register[\\w.]*;", Pattern.MULTILINE);

    @Test
    void everyMovedTypeLeftTheApplicationTree() {
        assertThat(Files.isDirectory(APPLICATION_INTERFACE)
                ? javaSources(APPLICATION_INTERFACE).stream().map(Path::toString).toList()
                : List.<String>of())
            .as("каталог стандартного интерфейса в приложении пуст после S4: файлы переезжали,"
                + " а не копировались — две копии адаптера означали бы, что приложение и артефакт"
                + " могут разойтись, и заметить это будет нечем")
            .isEmpty();
    }

    @Test
    void movedTypesAreResolvedFromTheArtifactAtRuntime() {
        for (Class<?> type : MOVED_TYPES) {
            var codeSource = type.getProtectionDomain().getCodeSource();
            assertThat(codeSource)
                .as("%s должен быть доступен приложению", type.getName())
                .isNotNull();
            assertThat(codeSource.getLocation().toString())
                .as("%s: класс обязан приходить из platform-register-vaadin, а не из"
                    + " target/classes приложения — иначе тесты проверяют не тот байткод",
                    type.getName())
                .contains("platform-register-vaadin");
        }
    }

    @Test
    void registrationOwnerIsTheModuleNotTheApplication() {
        assertThat(read(MODULE.resolve(MODULE_IMPORTS)))
            .as("авто-конфигурацию стандартного интерфейса регистрирует артефакт: приложение"
                + " не сканирует org.ipro, поэтому без этой строки адаптер форм исчез бы молча,"
                + " а формы списка регистров остались бы без режимов, колонок регистратора"
                + " и команды смены ключа")
            .contains(CONFIGURATION);
        assertThat(read(Path.of(APPLICATION_IMPORTS)))
            .as("приложение не регистрирует чужие авто-конфигурации: двойная регистрация делает"
                + " порядок применения неопределённым, а владельца — непроверяемым")
            .doesNotContain("org.ipro.vaadin.register");
    }

    @Test
    void applicationTakesTheStandardInterfaceThroughTheStarter() {
        Set<String> applicationArtifacts = declaredArtifacts(Path.of("pom.xml"));

        assertThat(applicationArtifacts)
            .as("поставка UI-платформы — стартер, а не набор внутренних модулей: прямая"
                + " зависимость на UI-адаптер вернула бы boilerplate, ради снятия которого"
                + " стартер и существует")
            .doesNotContain("platform-register-vaadin")
            .contains("platform-vaadin-starter");
        assertThat(declaredArtifacts(Path.of("platform-vaadin-starter/pom.xml")))
            .as("стандартный интерфейс регистра входит в замыкание UI-стартера — иначе приложение"
                + " обязано объявлять его само, и «объявил регистр — получил интерфейс» перестаёт"
                + " быть правдой")
            .contains("platform-register-vaadin");
    }

    @Test
    void applicationMainCodeDoesNotNameModuleTypes() {
        List<String> offenders = new ArrayList<>();
        for (Path source : javaSources(APPLICATION_MAIN_SOURCES)) {
            if (withoutComments(read(source)).contains("org.ipro.vaadin.register")) {
                offenders.add(APPLICATION_MAIN_SOURCES.relativize(source).toString().replace('\\', '/'));
            }
        }

        assertThat(offenders)
            .as("production-код приложения называет тип стандартного интерфейса: тогда адаптер"
                + " неотделим от приложения, и «приложение с другим UI не тянет Vaadin» становится"
                + " утверждением без проверки")
            .isEmpty();
    }

    @Test
    void consumerTestsStillCompileAgainstTheModule() {
        List<Path> consumers = javaSources(APPLICATION_TEST_SOURCES).stream()
            .filter(source -> MODULE_IMPORT.matcher(read(source)).find())
            .toList();

        assertThat(consumers)
            .as("потребительские тесты приложения: единственная опора приложения на артефакт —"
                + " тест пилота, который разрешает подписи регистратора. Пустой список означал бы,"
                + " что приёмка переноса держится на вере")
            .isNotEmpty();
    }

    @Test
    void applicationTestsDoNotOccupyTheModulesPackages() {
        Set<String> modulePackages = new TreeSet<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            Matcher matcher = PACKAGE.matcher(read(source));
            if (matcher.find()) {
                modulePackages.add(matcher.group(1));
            }
        }

        List<String> offenders = new ArrayList<>();
        for (Path source : javaSources(APPLICATION_TEST_SOURCES)) {
            Matcher matcher = PACKAGE.matcher(read(source));
            if (matcher.find() && modulePackages.contains(matcher.group(1))) {
                offenders.add(APPLICATION_TEST_SOURCES.relativize(source).toString().replace('\\', '/'));
            }
        }

        assertThat(offenders)
            .as("тест приложения живёт в пакете модуля — это test-side split package: внутри него"
                + " package-private тип модуля доступен через границу артефакта и невидим для"
                + " заборов. Тесты приложения живут в org.ip..")
            .isEmpty();
    }

    private static Set<String> declaredArtifacts(Path pom) {
        Set<String> artifacts = new TreeSet<>();
        Matcher matcher = Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(read(pom));
        while (matcher.find()) {
            artifacts.add(matcher.group(1));
        }
        return artifacts;
    }

    private static List<Path> javaSources(Path root) {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String withoutComments(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ");
    }

    private static String read(Path path) {
        if (!Files.exists(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
