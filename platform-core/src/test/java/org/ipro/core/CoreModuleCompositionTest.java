package org.ipro.core;

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
 * D3.3 (backend платформы): модуль проверяет свой состав сам.
 *
 * <p>Гейт владельца, а не потребителя: артефакт публикуется отдельно, поэтому его состав,
 * его зависимости и его чистота от кода приложения обязаны проверяться здесь. Кросс-артефактные
 * свойства (нет копии типа в дереве приложения, тип реально грузится из артефакта) остаются в
 * приложении — они требуют видеть обе стороны сразу.</p>
 *
 * <p>Почему группа такая крупная. {@code crud}, {@code data}, {@code fetch} и {@code metadata}
 * образуют цикл ({@code MetadataResolver -> crud.Standard*Entity},
 * {@code SectionMetadataRegistry -> crud.TableSectionService}, {@code crud -> data -> fetch},
 * {@code crud -> fetch}, {@code fetch -> metadata}), а пакет нельзя перенести частично: типы
 * одного пакета в двух артефактах — это split package, который компилятор различает по порядку
 * classpath, а не по замыслу. Поэтому цикл переезжает целиком — 74 класса, — а {@code security}
 * едет с ним, потому что {@code org.ipro.data} импортирует {@link org.ipro.security.CurrentUser}
 * и оставленный в дереве пакет превратил бы ребро в {@code core -> приложение}. Следом
 * переезжают {@code search} и {@code filter}: они зависят от цикла, но из цикла в них не
 * ссылается никто.</p>
 *
 * <p>Авто-конфигурации сюда не входят: пакеты {@code org.ipro.*.config} остаются в дереве
 * приложения до D3.4, поэтому потеря модуля видна компилятору, а не только в рантайме.</p>
 */
class CoreModuleCompositionTest {

    private static final Path MODULE = Path.of("").toAbsolutePath();

    /**
     * Reviewed-реестр состава: полный список типов, которые модуль публикует сегодня.
     *
     * <p>Список утверждён ревью, а не сгенерирован: тип, попавший сюда случайно, расширяет
     * публичную поверхность платформы, а пропавший — незаметно возвращает класс в дерево
     * приложения. Расширять его можно только вместе с переездом группы, названной в плане D3.3,
     * и это должно быть видно в diff как добавленные строки.</p>
     */
    private static final Set<String> REVIEWED_TYPES = Set.of(
        "org.ipro.crud.AggregateSaveRollbackState",
        "org.ipro.crud.BaseService",
        "org.ipro.crud.EntityCopyService",
        "org.ipro.crud.GenericOwnedSectionService",
        "org.ipro.crud.InternedEntity",
        "org.ipro.crud.LookupService",
        "org.ipro.crud.MetadataDrivenAggregateSaveService",
        "org.ipro.crud.MetadataTableSectionService",
        "org.ipro.crud.NaturalKeyCreateSupport",
        "org.ipro.crud.ReferenceCheckService",
        "org.ipro.crud.ServiceLocator",
        "org.ipro.crud.StandardCatalogEntity",
        "org.ipro.crud.StandardDocumentEntity",
        "org.ipro.crud.TableSectionService",
        "org.ipro.crud.ValidationException",
        "org.ipro.crud.jpa.ValidatedJpaCrudService",
        "org.ipro.data.CanonicalEntityDataAccess",
        "org.ipro.data.CanonicalEntityService",
        "org.ipro.data.CanonicalReadExecutor",
        "org.ipro.data.CanonicalWriteDeniedException",
        "org.ipro.data.CanonicalWriteExecutor",
        "org.ipro.data.DetailRead",
        "org.ipro.data.EntityCapabilities",
        "org.ipro.data.EntityDataAccess",
        "org.ipro.data.EntityDataAccessResolver",
        "org.ipro.data.EntityDataPolicy",
        "org.ipro.data.EntityDescriptor",
        "org.ipro.data.EntityDescriptorCatalog",
        "org.ipro.data.EventContourStartupCheck",
        "org.ipro.data.ListRead",
        "org.ipro.data.LookupRead",
        "org.ipro.data.PageRead",
        "org.ipro.data.ReadTelemetry",
        "org.ipro.data.ScenarioFetchGraphResolver",
        "org.ipro.data.SearchContext",
        "org.ipro.data.SearchFieldResolver",
        "org.ipro.data.SearchRead",
        "org.ipro.data.SearchTerms",
        "org.ipro.data.WriteTelemetry",
        "org.ipro.data.grouping.GroupingValuesProviderFactory",
        "org.ipro.data.grouping.JpaGroupingValuesProviderFactory",
        "org.ipro.fetch.ManagedEntityTypes",
        "org.ipro.fetch.instance.InstanceName",
        "org.ipro.fetch.instance.InstanceNameBridge",
        "org.ipro.fetch.instance.InstanceNameBridgeInstaller",
        "org.ipro.fetch.instance.InstanceNameProvider",
        "org.ipro.fetch.instance.InstanceNameResolver",
        "org.ipro.fetch.plan.FetchPlan",
        "org.ipro.fetch.plan.FetchPlanRegistry",
        "org.ipro.filter.ColumnPathFilterFieldResolver",
        "org.ipro.filter.FilterConditionCodec",
        "org.ipro.metadata.ColumnPath",
        "org.ipro.metadata.EntityMetadataInfo",
        "org.ipro.metadata.FactOrigin",
        "org.ipro.metadata.FetchGraphs",
        "org.ipro.metadata.FieldMetadataInfo",
        "org.ipro.metadata.FilterSpec",
        "org.ipro.metadata.GridMetadata",
        "org.ipro.metadata.GridViewState",
        "org.ipro.metadata.HasDisplayName",
        "org.ipro.metadata.InstanceNameSource",
        "org.ipro.metadata.ManagedEntityCatalog",
        "org.ipro.metadata.MetadataAllowance",
        "org.ipro.metadata.MetadataCache",
        "org.ipro.metadata.MetadataConsistencyStartupCheck",
        "org.ipro.metadata.MetadataConsistencyValidator",
        "org.ipro.metadata.MetadataDiagnostic",
        "org.ipro.metadata.MetadataDiagnosticCodes",
        "org.ipro.metadata.MetadataResolver",
        "org.ipro.metadata.RowMetadataInfo",
        "org.ipro.metadata.SectionMetadataRegistry",
        "org.ipro.metadata.SubsystemNode",
        "org.ipro.metadata.SubsystemRegistry",
        "org.ipro.metadata.TableSectionGridMetadata",
        "org.ipro.metadata.TableSectionMetadataInfo",
        "org.ipro.metadata.facet.FacetKey",
        "org.ipro.metadata.facet.FacetKind",
        "org.ipro.metadata.facet.FacetResolver",
        "org.ipro.metadata.facet.FactSource",
        "org.ipro.metadata.facet.ResolvedValue",
        "org.ipro.search.GlobalSearchCatalog",
        "org.ipro.search.GlobalSearchMatch",
        "org.ipro.search.GlobalSearchMatchKind",
        "org.ipro.search.GlobalSearchProvider",
        "org.ipro.search.GlobalSearchProviderRegistry",
        "org.ipro.search.GlobalSearchRequest",
        "org.ipro.search.GlobalSearchResponse",
        "org.ipro.search.GlobalSearchResult",
        "org.ipro.search.GlobalSearchService",
        "org.ipro.search.GlobalSearchSource",
        "org.ipro.search.GlobalSearchable",
        "org.ipro.search.JpaGlobalSearchProvider",
        "org.ipro.security.CurrentUser");

    /**
     * Reviewed внешние package-префиксы, допустимые в исходниках модуля. Это не «всё, что
     * скомпилировалось»: каждая строка ниже закрывает то, что модуль действительно использует,
     * и добавление нового корня обязано быть отдельным решением в ревью.
     *
     * <p>{@code org.ipro} здесь законен и означает «другой платформенный модуль или этот же».
     * Границу с приложением он не размывает: {@code org.ip} блокируется отдельным тестом,
     * который различает {@code org.ip} и {@code org.ipro} по границе слова.</p>
     *
     * <p>Проверки «нет Vaadin» здесь недостаточно и это не случайность: {@code platform-crud-api}
     * — это leaf-модуль реактора {@code crudui}, то есть модуль импортирует имя из
     * UI-библиотеки, не объявляя её. Разница принципиальная и её держат два теста вместе:
     * этот список доказывает, что объявлен именно контрактный артефакт ({@code platform-crud-api}),
     * а {@code moduleDeclaresOnlyTheReviewedCompileDependencies} — что в нём нет
     * {@code crudui-core}. Только вместе они означают «видит контракт, не видит UI-классов».</p>
     */
    private static final Set<String> ALLOWED_EXTERNAL_IMPORTS = Set.of(
        "com.fasterxml.jackson",
        "jakarta.persistence",
        "jakarta.transaction",
        "jakarta.validation",
        "org.hibernate",
        "org.ipro",
        "org.slf4j",
        "org.springframework");

    /**
     * Reviewed compile-поверхность: ровно то, что объявлено в POM вне test-scope. Модуль
     * публикуется отдельно, поэтому его зависимости — часть его контракта с потребителем.
     */
    private static final Set<String> REVIEWED_COMPILE_DEPENDENCIES = Set.of(
        "platform-identity-api",
        "platform-crud-api",
        "platform-contracts",
        "platform-events",
        "platform-persistence",
        "platform-metadata",
        "platform-numbering",
        "platform-telemetry",
        "platform-rls",
        "filtergrid-core",
        "filtergrid-grouping",
        "jakarta.persistence-api",
        "jakarta.validation-api",
        "jakarta.transaction-api",
        "spring-beans",
        "spring-context",
        "spring-core",
        "spring-tx",
        "spring-data-jpa",
        "spring-data-commons",
        "spring-security-core",
        "hibernate-core",
        "jackson-databind",
        "jackson-core",
        "slf4j-api");

    /**
     * Прикладной пакет как отдельное имя: без границ «org.ip» матчилось бы внутри «org.ipro.*» —
     * платформенных имён, которые здесь как раз законны.
     */
    private static final Pattern APPLICATION_PACKAGE =
        Pattern.compile("(?<![\\w.])org\\.ip(?![\\w])");

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern DEPENDENCY_BLOCK =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern PARENT_BLOCK = Pattern.compile("<parent>(.*?)</parent>", Pattern.DOTALL);

    @Test
    void moduleCarriesExactlyTheReviewedTypes() {
        assertThat(actualTypes())
            .as("состав модуля — reviewed-реестр: тип, попавший в модуль случайно, расширяет"
                + " публичную поверхность платформы, а пропавший — возвращает класс в дерево"
                + " приложения, и обе стороны файловых проверок остаются зелёными")
            .isEqualTo(new TreeSet<>(REVIEWED_TYPES));
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
                + " зависимость потребителя, а не деталь реализации")
            .isEmpty();
    }

    @Test
    void moduleDeclaresOnlyTheReviewedCompileDependencies() {
        assertThat(declaredDependencies())
            .as("compile-поверхность модуля reviewed: зависимость, объявленная раньше"
                + " использования, делает невидимым, что же модулю действительно нужно")
            .isEqualTo(new TreeSet<>(REVIEWED_COMPILE_DEPENDENCIES));
    }

    /**
     * Проверяются <b>объявленные зависимости</b>, а не текст POM.
     *
     * <p>Это не придирка: первая версия гейта искала подстроку «vaadin» в файле и падала на
     * собственном комментарии, который объясняет, почему Vaadin здесь запрещён. Гейт, который
     * ловит документацию вместо зависимости, заставляет удалять объяснение, чтобы он остался
     * зелёным — то есть учит ровно обратному тому, зачем написан.</p>
     */
    @Test
    void moduleDeclaresNoVaadinDependency() {
        String declared = String.join("\n", declaredDependencies()).toLowerCase(java.util.Locale.ROOT);
        assertThat(declared)
            .as("Vaadin живёт в platform-vaadin: попав в platform-core, он отрезает backend"
                + " от не-UI потребителя")
            .doesNotContain("vaadin");
    }

    /**
     * Ищется ссылка в <b>коде</b>, а не в тексте файла, и это не тонкость ради точности.
     *
     * <p>Первая версия гейта искала подстроку во всём файле и падала на комментарии
     * {@code } <code>находится вне org.ip.views</code> — то есть на объяснении того, что UI-слой
     * уже вынесен. Гейт, который ловит документацию, требует удалить объяснение, чтобы стать
     * зелёным: он учит ровно обратному тому, зачем написан, и заодно стирает из кода причину
     * проведённой границы. Так уже было дважды — с поиском «vaadin» в POM и с {@code crudui-core}.</p>
     *
     * <p>Стрипываются только комментарии. Строковые литералы остаются: имя пакета, зашитое
     * строкой (значение scan-package, имя класса для reflection), — это настоящая связь, а не
     * описание. Поэтому сканер учитывает кавычки и не принимает {@code //} внутри строки за
     * начало комментария.</p>
     */
    @Test
    void noSourceReferencesVaadinOrTheApplicationPackageInCode() {
        List<String> offenders = new ArrayList<>();
        for (Path source : javaSources(MODULE.resolve("src/main"))) {
            String code = withoutComments(read(source));
            if (code.contains("import com.vaadin") || code.contains("import static com.vaadin")
                || code.contains("com.vaadin.flow")) {
                offenders.add("vaadin: " + source.getFileName());
            }
            if (APPLICATION_PACKAGE.matcher(code).find()) {
                offenders.add("org.ip: " + source.getFileName());
            }
        }

        assertThat(offenders)
            .as("org.ip не может появляться в платформенном модуле ни кодом, ни строкой: иначе"
                + " модуль перестаёт быть модулем и становится частью приложения. Vaadin здесь"
                + " запрещён по имени пакета, а не по зависимости, потому что транзитивный приход"
                + " всё равно сделал бы backend неотделимым от UI")
            .isEmpty();
    }

    /**
     * Reviewed compile-зависимости: только блоки вне test-scope, без родителя и собственного
     * artifactId. Именно это попадает в публикуемый артефакт.
     */
    private static Set<String> declaredDependencies() {
        String pom = read(MODULE.resolve("pom.xml"));
        String withoutParent = PARENT_BLOCK.matcher(pom).replaceAll(" ");
        Set<String> declared = new TreeSet<>();
        Matcher blocks = DEPENDENCY_BLOCK.matcher(withoutParent);
        while (blocks.find()) {
            if (blocks.group().contains("<scope>test</scope>")) {
                continue;
            }
            Matcher artifact = ARTIFACT_ID.matcher(blocks.group());
            while (artifact.find()) {
                declared.add(artifact.group(1));
            }
        }
        declared.remove("platform-core");
        declared.remove("spring-boot-starter-parent");
        return declared;
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
