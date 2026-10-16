package org.ipro.vaadin.register;

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
 * D-R S4: модуль стандартного интерфейса регистра проверяет свой состав, свои зависимости
 * и свои запреты сам.
 *
 * <p>Гейт владельца, а не потребителя — по образцу {@code VaadinModuleCompositionTest}:
 * артефакт публикуется отдельно, поэтому его состав и его compile-поверхность обязаны
 * проверяться на его собственном classpath. Тесты приложения этого не увидят: для приложения
 * перенесённые типы выглядят обычными импортируемыми классами.</p>
 *
 * <p><b>Почему состав зафиксирован списком.</b> Роли назначены тем же изменением, что создало
 * артефакт (реестр {@code register-vaadin-surface.txt}), то есть решение о контракте принято
 * раньше переезда. Список отвечает за другое: что переехало ровно задуманное множество и что
 * новый тип не может появиться в публикуемом артефакте незамеченным.</p>
 *
 * <p><b>Что здесь есть сверх состава.</b> Направление «backend регистра без Vaadin»: срез S4 —
 * единственное место выноса, где Vaadin вообще появляется, и обязано быть видно, что он не
 * протёк в {@code platform-register}. Зависимость односторонняя: этот артефакт зависит от
 * backend-модуля, backend-модуль об интерфейсе не знает.</p>
 */
class RegisterVaadinModuleCompositionTest {

    private static final Path MODULE = Path.of("").toAbsolutePath();

    private static final Path REGISTER_BACKEND = MODULE.getParent().resolve("platform-register");

    /** Reviewed-реестр состава: 5 перенесённых S4 типов и конфигурация среза. */
    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.vaadin.register.RecorderRefDisplay",
        "org.ipro.vaadin.register.RecorderRefPresentation",
        "org.ipro.vaadin.register.RecorderRefPresentationResolver",
        "org.ipro.vaadin.register.RegisterFormCustomization",
        "org.ipro.vaadin.register.RegisterKeyDialog",
        "org.ipro.vaadin.register.config.RegisterVaadinConfiguration");

    private static final Set<String> ALLOWED_EXTERNAL_IMPORTS = Set.of(
        "com.fasterxml.jackson",
        "com.vaadin",
        "org.ipro",
        "org.slf4j",
        "org.springframework");

    private static final Set<String> REVIEWED_COMPILE_DEPENDENCIES = Set.of(
        "platform-register",
        "platform-vaadin",
        "platform-core",
        "platform-persistence",
        "platform-identity-api",
        "filtergrid-core",
        "vaadin-spring-boot-starter",
        "spring-context",
        "spring-beans",
        "spring-boot-autoconfigure",
        "spring-data-commons",
        "spring-data-jpa",
        "spring-security-core",
        "jackson-databind",
        "slf4j-api");

    private static final Set<String> FORBIDDEN_REPORT_ROOTS = Set.of(
        "org.ipro.reportstudio",
        "org.ipro.jr",
        "org.ipro.ureport",
        "org.ipro.reportui",
        "org.ipro.dynamicreports",
        "com.ureport",
        "net.sf.jasperreports",
        "ar.com.fdvs");

    /** Прикладной пакет как отдельное имя: без границ «org.ip» матчилось бы внутри «org.ipro.*». */
    private static final Pattern APPLICATION_PACKAGE = Pattern.compile("(?<![\\w.])org\\.ip(?![\\w])");

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern DEPENDENCY_BLOCK =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern DEPENDENCY_MANAGEMENT =
        Pattern.compile("<dependencyManagement>.*?</dependencyManagement>", Pattern.DOTALL);
    private static final Pattern PARENT_BLOCK =
        Pattern.compile("<parent>(.*?)</parent>", Pattern.DOTALL);
    private static final Pattern IMPORTS_RESOURCE = Pattern.compile(
        "^\\s*([\\w.]+)\\s*$", Pattern.MULTILINE);

    private static final Path AUTO_CONFIGURATION_IMPORTS = MODULE
        .resolve("src/main/resources/META-INF/spring")
        .resolve("org.springframework.boot.autoconfigure.AutoConfiguration.imports");

    private static final Set<String> REVIEWED_AUTO_CONFIGURATIONS = Set.of(
        "org.ipro.vaadin.register.config.RegisterVaadinConfiguration");

    @Test
    void moduleCarriesExactlyTheReviewedTypes() {
        assertThat(actualTypes())
            .as("состав модуля — reviewed-реестр: тип, попавший сюда случайно, расширяет поверхность"
                + " платформы, а пропавший возвращает класс в дерево приложения, и обе стороны"
                + " файловых проверок при этом остаются зелёными")
            .isEqualTo(new TreeSet<>(REVIEWED_TYPES));
        assertThat(actualTypes())
            .as("забор не должен быть вакуумным: 5 типов перенёс S4 и одну конфигурацию он добавил")
            .hasSize(6);
    }

    @Test
    void moduleImportsOnlyReviewedExternalRoots() {
        List<String> foreignImports = new ArrayList<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            Matcher matcher = IMPORT.matcher(read(source));
            while (matcher.find()) {
                String imported = matcher.group(1);
                if (imported.startsWith("java.")) {
                    continue;
                }
                boolean allowed = ALLOWED_EXTERNAL_IMPORTS.stream()
                    .anyMatch(prefix -> imported.startsWith(prefix + "."))
                    || REVIEWED_TYPES.contains(imported);
                if (!allowed) {
                    foreignImports.add(source.getFileName() + " -> " + imported);
                }
            }
        }

        assertThat(foreignImports)
            .as("внешняя compile-поверхность модуля reviewed: новый корень импорта — это новая"
                + " зависимость каждого потребителя стандартного интерфейса, а не деталь реализации")
            .isEmpty();
    }

    @Test
    void moduleDeclaresOnlyTheReviewedCompileDependencies() {
        assertThat(declaredDependencies())
            .as("compile-поверхность модуля reviewed: зависимость, объявленная раньше"
                + " использования, делает невидимым, что же модулю действительно нужно")
            .isEqualTo(new TreeSet<>(REVIEWED_COMPILE_DEPENDENCIES));
        assertThat(read(MODULE.resolve("pom.xml")))
            .as("Vaadin объявлен scope provided: UI-стек не навязывается потребителю транзитивно —"
                + " это обещание видно в POM, а не только в описании")
            .contains("<artifactId>vaadin-spring-boot-starter</artifactId>")
            .contains("<scope>provided</scope>");
    }

    @Test
    void noSourceReferencesTheApplicationPackageInCode() {
        List<String> offenders = new ArrayList<>();
        for (Path source : javaSources(MODULE.resolve("src/main"))) {
            String code = withoutComments(read(source));
            if (APPLICATION_PACKAGE.matcher(code).find()) {
                offenders.add(source.getFileName().toString());
            }
        }

        assertThat(offenders)
            .as("org.ip не может появляться в платформенном модуле ни кодом, ни строкой: прикладной"
                + " пакет в артефакте замкнул бы цикл сборки. Именно поэтому адаптер переехал со"
                + " сменой пакета (org.ip.views.register -> org.ipro.vaadin.register), а не"
                + " с сохранением FQN")
            .isEmpty();
    }

    @Test
    void moduleDoesNotImportReportSubsystems() {
        List<String> offenders = new ArrayList<>();
        for (Path source : javaSources(MODULE.resolve("src/main"))) {
            String code = withoutComments(read(source));
            for (String forbidden : FORBIDDEN_REPORT_ROOTS) {
                if (code.contains(forbidden)) {
                    offenders.add(source.getFileName() + " -> " + forbidden);
                }
            }
        }

        assertThat(offenders)
            .as("отчётность остаётся потребителем формы (направление Report->Form), а не её"
                + " зависимостью: подключение DR/JR/UReport в стандартный интерфейс протянуло бы"
                + " их в каждого потребителя форм")
            .isEmpty();
    }

    @Test
    void moduleRegistersItsOwnAutoConfiguration() {
        Set<String> declared = new TreeSet<>();
        Matcher matcher = IMPORTS_RESOURCE.matcher(read(AUTO_CONFIGURATION_IMPORTS));
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }

        assertThat(declared)
            .as("конфигурацию среза регистрирует этот артефакт: приложение не сканирует org.ipro,"
                + " поэтому без записи стандартный интерфейс исчез бы молча, а не громко")
            .isEqualTo(new TreeSet<>(REVIEWED_AUTO_CONFIGURATIONS));

        List<String> missing = new ArrayList<>();
        for (String fqn : declared) {
            Path source = MODULE.resolve("src/main/java").resolve(fqn.replace('.', '/') + ".java");
            if (!Files.exists(source)) {
                missing.add(fqn);
            }
        }
        assertThat(missing)
            .as("запись без класса = падение на старте с ClassNotFoundException: тип уехал,"
                + " а регистрация осталась")
            .isEmpty();
    }

    @Test
    void backendRegisterModuleKnowsNothingAboutTheInterface() {
        assertThat(declaredDependencies(REGISTER_BACKEND.resolve("pom.xml")))
            .as("platform-register не может зависеть от UI-адаптера: срез S4 обязан быть"
                + " односторонним, иначе вынос интерфейса вернул бы Vaadin в backend-подсистему,"
                + " а вместе с ним — «приложение с любым интерфейсом тянет Vaadin»")
            .noneMatch(artifact -> artifact.contains("vaadin"));

        List<String> imports = new ArrayList<>();
        for (Path source : javaSources(REGISTER_BACKEND.resolve("src/main/java"))) {
            String code = withoutComments(read(source));
            if (code.contains("com.vaadin") || code.contains("org.ipro.vaadin")) {
                imports.add(source.getFileName().toString());
            }
        }
        assertThat(imports)
            .as("backend регистра не знает Vaadin ни артефактом, ни импортом: интерфейс живёт"
                + " в отдельном модуле, и это его единственная граница обещания")
            .isEmpty();
    }

    @Test
    void moduleTestsStayFreeOfTheApplicationAndReport() {
        // Свой файл исключён поимённо и ровно один — тем же приёмом, что в VaadinModuleCompositionTest:
        // забор обязан назвать запрещённое, а шаблон org\.ip и корни отчётности внутри него — это
        // и есть название. Обход, спрятанный в правиле («снимать литералы всем»), отключил бы
        // проверку целиком; названное исключение видно в диффе.
        Set<String> ownSources = Set.of("RegisterVaadinModuleCompositionTest.java");
        List<String> offenders = new ArrayList<>();
        for (Path source : javaSources(MODULE.resolve("src/test"))) {
            if (ownSources.contains(source.getFileName().toString())) {
                continue;
            }
            String code = withoutComments(read(source));
            if (APPLICATION_PACKAGE.matcher(code).find()) {
                offenders.add(source.getFileName() + " -> org.ip");
            }
            for (String forbidden : FORBIDDEN_REPORT_ROOTS) {
                if (code.contains(forbidden)) {
                    offenders.add(source.getFileName() + " -> " + forbidden);
                }
            }
        }

        assertThat(offenders)
            .as("тест модуля зависит от приложения или отчётности: тогда модуль собирается и"
                + " проверяется только вместе с приложением, а «перенос» держится на том, что"
                + " тест забыли перенести. Именно поэтому тест диалога переехал с модульными"
                + " фикстурами, а не с прикладными сущностями")
            .isEmpty();
    }

    @Test
    void ownerSideTestsDoNotShrink() {
        assertThat(javaSources(MODULE.resolve("src/test")))
            .as("owner-side тесты модуля: поверхность ролей, состав, два теста перенесённых"
                + " классов. Порог — тот же смысл, что пороги D1: бюджет только сокращается,")
            .hasSizeGreaterThanOrEqualTo(4);
    }

    private static Set<String> declaredDependencies() {
        return declaredDependencies(MODULE.resolve("pom.xml"));
    }

    private static Set<String> declaredDependencies(Path pomPath) {
        String pom = read(pomPath);
        String withoutParent = PARENT_BLOCK.matcher(pom).replaceAll(" ");
        String text = DEPENDENCY_MANAGEMENT.matcher(withoutParent).replaceAll(" ");
        Set<String> declared = new TreeSet<>();
        Matcher blocks = DEPENDENCY_BLOCK.matcher(text);
        while (blocks.find()) {
            if (blocks.group().contains("<scope>test</scope>")) {
                continue;
            }
            Matcher artifact = ARTIFACT_ID.matcher(blocks.group());
            while (artifact.find()) {
                declared.add(artifact.group(1));
            }
        }
        declared.remove("platform-register-vaadin");
        declared.remove("spring-boot-starter-parent");
        return declared;
    }

    private static Set<String> actualTypes() {
        Set<String> types = new TreeSet<>();
        for (Path source : javaSources(MODULE.resolve("src/main/java"))) {
            types.add(packageOf(source) + "." + typeOf(source));
        }
        return types;
    }

    private static String typeOf(Path source) {
        String name = source.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }

    private static String packageOf(Path source) {
        Matcher matcher = PACKAGE.matcher(read(source));
        if (!matcher.find()) {
            throw new IllegalStateException("нет package: " + source);
        }
        return matcher.group(1);
    }

    /** Убирает комментарии, сохраняя строковые литералы: {@code org.ip} в комментарии — не связь. */
    private static String withoutComments(String source) {
        StringBuilder result = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (current == '"') {
                boolean textBlock = source.startsWith("\"\"\"", index);
                String delimiter = textBlock ? "\"\"\"" : "\"";
                result.append(delimiter);
                index += delimiter.length();
                while (index < source.length()) {
                    if (source.startsWith(delimiter, index) && !isEscaped(source, index)) {
                        result.append(delimiter);
                        index += delimiter.length();
                        break;
                    }
                    result.append(source.charAt(index));
                    index++;
                }
                continue;
            }
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                while (index < source.length() && source.charAt(index) != '\n') {
                    index++;
                }
                continue;
            }
            if (current == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                int closing = source.indexOf("*/", index + 2);
                index = closing < 0 ? source.length() : closing + 2;
                continue;
            }
            result.append(current);
            index++;
        }
        return result.toString();
    }

    private static boolean isEscaped(String source, int index) {
        int backslashes = 0;
        for (int probe = index - 1; probe >= 0 && source.charAt(probe) == '\\'; probe--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
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

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
