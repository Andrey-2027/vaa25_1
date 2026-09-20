package org.ipro.vaadin.module;

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
 * D3.5.4: модуль UI-слоя проверяет свой состав и свои зависимости сам.
 *
 * <p>Гейт владельца, а не потребителя — по образцу {@code CoreModuleCompositionTest}
 * и {@code AutoconfigureModuleCompositionTest}. Артефакт публикуется отдельно, поэтому его
 * состав, его compile-поверхность и его чистота обязаны проверяться здесь, на его собственном
 * classpath: тесты приложения этого не увидят, потому что для приложения все 93 типа выглядят
 * как обычные импортируемые классы.</p>
 *
 * <p><b>Почему состав зафиксирован списком, а не «всё, что скомпилировалось».</b> Роли типов
 * назначены до переноса (реестр {@code platform-vaadin-surface.txt}, D3.5.0) — то есть решение
 * о том, что является контрактом, принято раньше, чем переезд. Список здесь отвечает за другое:
 * что переехало ровно задуманное множество и что новый тип не может появиться в публикуемом
 * артефакте, не будучи замеченным в diff'е как добавленная строка.</p>
 *
 * <p><b>Что этот забор не проверяет.</b> Направление «приложение называет тип» держит D1-реестр
 * в дереве приложения, а принадлежность типа модулю — consumer-забор
 * {@code PlatformVaadinModuleTest}. Здесь только состав, объявленные зависимости и запреты.</p>
 */
class VaadinModuleCompositionTest {

    private static final Path MODULE = Path.of("").toAbsolutePath();

    /**
     * Reviewed-реестр состава: 93 типа, перенесённых D3.5.4 (form 83, vaadin 7, telemetry 3 —
     * последние три переехали вместе со сменой пакета {@code org.ip.telemetry.vaadin} →
     * {@code org.ipro.vaadin.telemetry}).
     *
     * <p>Расширять можно только вместе с переездом названной группы плана D3.5. Пропавшая строка
     * означает, что тип вернулся в дерево приложения — и тогда обе стороны файловых проверок
     * останутся зелёными, а границы не будет.</p>
     */
    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.form.BindingDescriptor",
        "org.ipro.form.Dirtyable",
        "org.ipro.form.EntityField",
        "org.ipro.form.FieldFactory",
        "org.ipro.form.FieldRenderer",
        "org.ipro.form.FilterGridMoreMenu",
        "org.ipro.form.FilterLookupOptions",
        "org.ipro.form.FormBinding",
        "org.ipro.form.FormBindingRegistry",
        "org.ipro.form.FormSaveHandler",
        "org.ipro.form.FormSaveResult",
        "org.ipro.form.ItemFormSaveHandler",
        "org.ipro.form.ItemFormSaveHandlerRegistry",
        "org.ipro.form.LookupComboHelper",
        "org.ipro.form.MetadataDrivenItemFormSaveAdapter",
        "org.ipro.form.Savable",
        "org.ipro.form.SearchFunction",
        "org.ipro.form.SectionPayload",
        "org.ipro.form.SectionPresence",
        "org.ipro.form.SelectionForm",
        "org.ipro.form.SelectionFormAssembler",
        "org.ipro.form.SelectionFormFactory",
        "org.ipro.form.SelectionGridCustomizer",
        "org.ipro.form.TableSectionCustomization",
        "org.ipro.form.TableSectionFactory",
        "org.ipro.form.builder.ContextFilterControl",
        "org.ipro.form.builder.ContextFilterField",
        "org.ipro.form.builder.ContextFilterPanel",
        "org.ipro.form.builder.ItemFormCustomization",
        "org.ipro.form.builder.ItemFormCustomizationRegistrar",
        "org.ipro.form.builder.ItemFormCustomizer",
        "org.ipro.form.builder.ItemFormVariants",
        "org.ipro.form.builder.ListFormCustomization",
        "org.ipro.form.builder.ListFormCustomizationRegistrar",
        "org.ipro.form.builder.ListFormCustomizer",
        "org.ipro.form.builder.ListFormVariants",
        "org.ipro.form.builder.SelectionColumnResolver",
        "org.ipro.form.builder.SelectionContextFilters",
        "org.ipro.form.builder.SelectionFormCustomization",
        "org.ipro.form.builder.SelectionFormCustomizationRegistrar",
        "org.ipro.form.builder.SelectionFormVariants",
        "org.ipro.form.builder.layout.CustomNode",
        "org.ipro.form.builder.layout.DisplayNode",
        "org.ipro.form.builder.layout.FieldNode",
        "org.ipro.form.builder.layout.ItemFormLayout",
        "org.ipro.form.builder.layout.LayoutNode",
        "org.ipro.form.builder.layout.PanelNode",
        "org.ipro.form.builder.layout.TabDefinition",
        "org.ipro.form.builder.layout.TabSheetNode",
        "org.ipro.form.builtin.GridViewEditorDialog",
        "org.ipro.form.builtin.ItemForm",
        "org.ipro.form.builtin.ItemHistory",
        "org.ipro.form.builtin.ItemSectionHost",
        "org.ipro.form.builtin.ItemTable",
        "org.ipro.form.builtin.ListForm",
        "org.ipro.form.builtin.ListFormVisualFilterAdapter",
        "org.ipro.form.builtin.RowDraft",
        "org.ipro.form.builtin.ViewSelectorDialog",
        "org.ipro.form.config.FormAutoConfiguration",
        "org.ipro.form.coordinator.FormCoordinator",
        "org.ipro.form.coordinator.FormNavigator",
        "org.ipro.form.coordinator.FormOpenMode",
        "org.ipro.form.coordinator.ItemFormAccessBinder",
        "org.ipro.form.coordinator.ItemFormWrapperView",
        "org.ipro.form.coordinator.ListFormWrapper",
        "org.ipro.form.registry.FormContext",
        "org.ipro.form.registry.FormFactory",
        "org.ipro.form.registry.FormKey",
        "org.ipro.form.registry.FormRegistry",
        "org.ipro.form.registry.FormRegistryConfiguration",
        "org.ipro.form.registry.FormResolver",
        "org.ipro.form.registry.FormType",
        "org.ipro.form.registry.ListCommand",
        "org.ipro.form.registry.ListCommandContext",
        "org.ipro.form.registry.ListCommandRegistry",
        "org.ipro.form.registry.ListFormContext",
        "org.ipro.form.registry.SelectionColumnsDef",
        "org.ipro.form.registry.SelectionFilter",
        "org.ipro.form.spi.FormSettingsStore",
        "org.ipro.form.spi.GridView",
        "org.ipro.form.spi.GridViewStore",
        "org.ipro.form.spi.ListFormToolbarContributor",
        "org.ipro.form.spi.WorkspaceGateway",
        "org.ipro.vaadin.explorer.EntitySummary",
        "org.ipro.vaadin.explorer.EntitySummaryAssembler",
        "org.ipro.vaadin.explorer.SubsystemSummaryAssembler",
        "org.ipro.vaadin.explorer.config.EntityExplorerAutoConfiguration",
        "org.ipro.vaadin.search.GlobalSearchHeader",
        "org.ipro.vaadin.search.GlobalSearchNavigationAdapter",
        "org.ipro.vaadin.search.config.GlobalSearchVaadinAutoConfiguration",
        "org.ipro.vaadin.telemetry.TelemetryErrorHandler",
        "org.ipro.vaadin.telemetry.TelemetryVaadinConfiguration",
        "org.ipro.vaadin.telemetry.TelemetryVaadinInitListener");

    /**
     * Reviewed внешние корни импорта. {@code org.ipro} здесь означает «другой платформенный
     * модуль или этот же»; {@code com.vaadin} — собственная технология модуля (в отличие от
     * platform-core, где Vaadin запрещён именно потому, что UI выделен сюда).
     */
    private static final Set<String> ALLOWED_EXTERNAL_IMPORTS = Set.of(
        "com.fasterxml.jackson",
        "com.vaadin",
        "org.ipro",
        "org.slf4j",
        "org.springframework");

    /**
     * Reviewed compile-поверхность: ровно то, что объявлено в POM вне test-scope. Для
     * UI-библиотеки это не формальность: {@code provided} у Vaadin — обещание потребителю, что
     * UI-стек не навязывается транзитивно, и оно обязано быть видно в списке, а не только в POM.
     */
    private static final Set<String> REVIEWED_COMPILE_DEPENDENCIES = Set.of(
        "platform-identity-api",
        "platform-core",
        "platform-metadata",
        "platform-events",
        "platform-numbering",
        "platform-telemetry",
        "platform-rls",
        "platform-spring-boot-autoconfigure",
        "filtergrid-core",
        "filtergrid-grouping",
        "filtergrid-jpa",
        "vaadin-spring-boot-starter",
        "spring-context",
        "spring-beans",
        "spring-core",
        "spring-boot",
        "spring-boot-autoconfigure",
        "spring-data-jpa",
        "spring-orm",
        "spring-security-core",
        "jackson-databind",
        "slf4j-api");

    /**
     * Подсистемы, которые обязаны остаться <b>потребителями</b> модуля. Отчётность переезжает
     * отдельным шагом (D3.5.7), и до него соблазн «заодно подключить» DR/JR/UReport велик ровно
     * так же, как он был велик для Vaadin в platform-core. Запрет называет и чужие артефакты:
     * попадание в POM тянет их в каждого потребителя UI-слоя.
     */
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
    private static final Pattern APPLICATION_PACKAGE =
        Pattern.compile("(?<![\\w.])org\\.ip(?![\\w])");

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

    /** Конфигурации UI-слоя: владелец регистрации — этот артефакт, и их ровно четыре. */
    private static final Set<String> REVIEWED_AUTO_CONFIGURATIONS = Set.of(
        "org.ipro.form.config.FormAutoConfiguration",
        "org.ipro.vaadin.explorer.config.EntityExplorerAutoConfiguration",
        "org.ipro.vaadin.search.config.GlobalSearchVaadinAutoConfiguration",
        "org.ipro.vaadin.telemetry.TelemetryVaadinConfiguration");

    @Test
    void moduleCarriesExactlyTheReviewedTypes() {
        assertThat(actualTypes())
            .as("состав модуля — reviewed-реестр: тип, попавший сюда случайно, расширяет публичную"
                + " поверхность платформы, а пропавший возвращает класс в дерево приложения, и обе"
                + " стороны файловых проверок при этом остаются зелёными")
            .isEqualTo(new TreeSet<>(REVIEWED_TYPES));
        assertThat(actualTypes())
            .as("забор не должен быть вакуумным: 93 типа — это замер D3.5.0/1/2/2b плюс перенос"
                + " D3.5.4 (form 83, vaadin 7, telemetry 3), а не догадка")
            .hasSize(93);
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
                + " зависимость каждого потребителя UI-слоя, а не деталь реализации")
            .isEmpty();
    }

    @Test
    void moduleDeclaresOnlyTheReviewedCompileDependencies() {
        assertThat(declaredDependencies())
            .as("compile-поверхность модуля reviewed: зависимость, объявленная раньше"
                + " использования, делает невидимым, что же модулю действительно нужно")
            .isEqualTo(new TreeSet<>(REVIEWED_COMPILE_DEPENDENCIES));
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
            .as("org.ip не может появляться в платформенном модуле ни кодом, ни строкой: иначе"
                + " модуль перестаёт быть модулем и становится частью приложения. Именно поэтому"
                + " telemetry-адаптер переехал со сменой пакета (org.ip.telemetry.vaadin →"
                + " org.ipro.vaadin.telemetry), а не с сохранением FQN")
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
            .as("отчётность остаётся потребителем UI-слоя (направление Report→Form), а не его"
                + " зависимостью: подключение DR/JR/UReport в platform-vaadin протянуло бы их"
                + " в каждого потребителя формы и, что хуже, закрепило бы обратное направление")
            .isEmpty();
    }

    @Test
    void moduleRegistersItsOwnAutoConfigurations() {
        Set<String> declared = new TreeSet<>();
        Matcher matcher = IMPORTS_RESOURCE.matcher(read(AUTO_CONFIGURATION_IMPORTS));
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }

        assertThat(declared)
            .as("конфигурации UI-слоя регистрирует этот артефакт: приложение не сканирует"
                + " org.ipro, поэтому незарегистрированная конфигурация молча выключает подсистему"
                + " (для telemetry-адаптера это ещё и тихая потеря ошибок UI в журнале)")
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

    /**
     * Reviewed compile-зависимости: только блоки вне test-scope, без родителя, без
     * {@code dependencyManagement} и без собственного artifactId.
     *
     * <p>{@code dependencyManagement} вырезан потому, что это объявление версий, а не
     * зависимость: {@code vaadin-bom} управляет версиями Vaadin-стека и в артефакт не входит —
     * ровно по той же причине его игнорирует манифест воркспейса.</p>
     */
    /**
     * D3.5.8: тесты владельца не зависят от приложения и от отчётности — то же правило, что для
     * {@code src/main}, распространённое на {@code src/test}.
     *
     * <p>Почему это не формальность. Тест модуля, которому нужна прикладная сущность, означает,
     * что модуль верифицируется только внутри приложения — то есть ровно то, от чего D3.5.4 уходил.
     * Переезд тестов в D3.5.4 дал 42 файла без единой ссылки на {@code org.ip}; свойство держалось
     * на дисциплине, пока D3.5.8 не сделал его проверяемым.</p>
     *
     * <p><b>Литералы здесь не снимаются, и это строже, а не мягче.</b> Импортом тест модуля на
     * приложение сослаться не может — сборка упадёт раньше забора; значит единственная реальная
     * связка в тесте — та, что компилируется молча: {@code Class.forName("org.ip.model...")},
     * путь к ресурсу, имя свойства. Именно её и надо ловить, а она живёт в литерале. Поэтому
     * правило совпадает с правилом для {@code src/main} («{@code org.ip} не может появляться ни
     * кодом, ни строкой»).</p>
     *
     * <p>Свой файл исключён <b>поимённо и ровно один</b>: забор обязан назвать запрещённое, а
     * шаблон {@code org\.ip} внутри него — то самое название. Обход, спрятанный в правиле
     * («снимать литералы всем»), отключил бы проверку целиком; названное исключение — видно в
     * диффе, и второе такое появится тоже только явно.</p>
     */
    @Test
    void moduleTestsStayFreeOfTheApplicationAndReport() {
        Set<String> ownSources = Set.of("VaadinModuleCompositionTest.java");
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
                + " проверяется только вместе с приложением, а «перенос» держится на том, что тест"
                + " забыли перенести")
            .isEmpty();
    }

    /**
     * D3.5.8: owner-side тесты не могут тихо сокращаться.
     *
     * <p>Половина смысла D3.5.4 в том, что состав и зависимости модуля проверяет его собственный
     * прогон. Тест, уехавший обратно в приложение или исчезнувший, вернул бы проверку туда, где
     * её видит только полный сборка приложения, — и это должно быть решением, а не диффом.</p>
     */
    @Test
    void ownerSideTestsDoNotShrink() {
        assertThat(javaSources(MODULE.resolve("src/test")))
            .as("owner-side тесты модуля: замерено 42 файла (D3.5.8). Порог — тот же смысл, что"
                + " пороги в D1: бюджет только сокращается, и сокращение обязано быть названо")
            .hasSizeGreaterThanOrEqualTo(42);
    }

    private static Set<String> declaredDependencies() {
        String pom = read(MODULE.resolve("pom.xml"));
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
        declared.remove("platform-vaadin");
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

    /**
     * Убирает комментарии, сохраняя строковые литералы и текст блоков.
     *
     * <p>Простая замена регуляркой здесь не годится по двум причинам: {@code /*} внутри строки
     * (URL, шаблон) начал бы «комментарий» и съел бы остаток файла, а {@code //} внутри строки
     * съел бы остаток строки — вместе с возможной настоящей ссылкой на {@code org.ip}.</p>
     */
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
