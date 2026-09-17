package org.ipro;

import org.ipro.crud.BaseService;
import org.ipro.crud.ServiceLocator;
import org.ipro.data.CanonicalEntityService;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.fetch.plan.FetchPlanRegistry;
import org.ipro.filter.FilterConditionCodec;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.facet.FacetKey;
import org.ipro.metadata.facet.FacetResolver;
import org.ipro.search.GlobalSearchCatalog;
import org.ipro.search.GlobalSearchService;
import org.ipro.security.CurrentUser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3.3: backend платформы — отдельный артефакт, и это проверяется с обеих сторон.
 *
 * <p>Границей этого шага была не «очевидная» группировка, а цикл. Замер рёбер показал, что
 * {@code org.ipro.metadata}, {@code org.ipro.crud}, {@code org.ipro.data} и {@code org.ipro.fetch}
 * зависят друг от друга ({@code MetadataResolver -> crud.StandardCatalogEntity},
 * {@code SectionMetadataRegistry -> crud.TableSectionService}, {@code crud -> data -> fetch},
 * {@code crud -> fetch}, {@code fetch -> metadata}), а пакет нельзя перенести частично: типы
 * одного пакета в двух артефактах — это split package, который компилятор различает по порядку
 * classpath, а не по замыслу. Поэтому цикл переехал целиком, а {@code search} и {@code filter}
 * — следом: они зависят от цикла, но из цикла в них не ссылается никто.</p>
 *
 * <p>Этот тест держит то, что видит приложение: reviewed-состав модуля, reviewed-зависимости,
 * отсутствие типов в дереве приложения, направление зависимости на UI-библиотеку crudui и —
 * главное — что типы действительно приходят из артефакта, а не из {@code target/classes}.</p>
 */
class PlatformCoreModuleTest {

    private static final Path MODULE = Path.of("platform-core");

    /**
     * Reviewed-реестр состава: полный list типов, которые артефакт публикует сегодня. Те же
     * 93 типа проверяет {@code CoreModuleCompositionTest} у себя — две стороны одной границы.
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

    /** Reviewed compile-поверхность модуля — то же, что проверяет его собственный тест. */
    private static final Set<String> REVIEWED_DEPENDENCIES = Set.of(
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

    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern DEPENDENCY_BLOCK =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern PARENT_BLOCK = Pattern.compile("<parent>(.*?)</parent>", Pattern.DOTALL);

    @Test
    void moduleCarriesExactlyTheReviewedTypes() {
        Set<String> actual = new TreeSet<>();
        for (Path source : javaSources()) {
            actual.add(packageOf(source) + "." + typeOf(source));
        }

        assertThat(actual)
            .as("состав модуля — reviewed-бюджет: тип, попавший в модуль случайно, расширяет"
                + " публичную поверхность платформы, а пропавший — незаметно возвращает класс"
                + " в дерево приложения")
            .isEqualTo(new TreeSet<>(REVIEWED_TYPES));
    }

    @Test
    void moduleDeclaresOnlyReviewedDependencies() {
        assertThat(declaredDependencies())
            .as("compile-поверхность модуля reviewed: зависимость, объявленная раньше"
                + " использования, делает невидимым, что же модулю действительно нужно")
            .isEqualTo(new TreeSet<>(REVIEWED_DEPENDENCIES));
    }

    /**
     * Направление владения: модуль видит контракт и не видит UI-классов.
     *
     * <p>Проверка «нет Vaadin» здесь ничего не доказывала бы: {@code platform-crud-api} — это
     * модуль реактора {@code crudui}, и зависимости от него достаточно, чтобы слово «UI» не
     * появлялось в POM ни разу. Содержательный вопрос другой — <b>какой именно</b> артефакт
     * объявлен. {@code crudui-core} — тот, из-за которого backend зависел от UI-библиотеки;
     * {@code platform-crud-api} — вынесенный из него контракт, который знает только
     * {@code IdentifiableEntity}.</p>
     *
     * <p>Поиск идёт по <b>объявленным зависимостям</b>, а не по тексту POM, и это третий случай
     * одной и той же ошибки за D-этапы (до этого — подстрока «vaadin» и подстрока «crudui-core»).
     * Комментарий в POM объясняет, <i>почему</i> артефакт crudui-core здесь запрещён, и содержит
     * его имя; гейт на подстроку требовал удалить объяснение, чтобы остаться зелёным.</p>
     */
    @Test
    void moduleDependsOnTheCrudContractButNotOnTheCrudUiArtifact() {
        Set<String> declared = declaredDependencies();
        assertThat(declared).contains("platform-crud-api");
        assertThat(declared).doesNotContain("crudui-core");
        assertThat(declared).doesNotContain("crudui-demo");
    }

    @Test
    void moduleDeclaresNoVaadinDependency() {
        String declared = String.join("\n", declaredDependencies()).toLowerCase(Locale.ROOT);
        assertThat(declared)
            .as("Vaadin живёт в platform-vaadin: попав в platform-core, он отрезает backend"
                + " от не-UI потребителя")
            .doesNotContain("vaadin");
    }

    @Test
    void sliceTypesLeftTheApplicationTree() {
        for (String type : REVIEWED_TYPES) {
            Path inApplication = Path.of("src/main/java", type.replace('.', '/') + ".java");
            assertThat(inApplication)
                .as("%s обязан быть только в модуле: копия в дереве делает файловые проверки"
                    + " зелёными при неработающем выносе", type)
                .doesNotExist();
        }
    }

    /**
     * Проверка исходников говорит, где типы объявлены; эта — откуда они берутся на самом деле.
     * Разница существенна: копия в дереве приложения оставила бы предыдущий тест зелёным, если
     * бы модуль молча не собирался.
     *
     * <p>Проверяются типы из разных групп переезда, а не пять представителей одного пакета:
     * {@code facet} мог переехать удачно, а {@code crud}/{@code data}/{@code metadata} — нет,
     * и тогда набор проверок остался бы зелёным на удачной части.</p>
     */
    @Test
    void sliceTypesAreResolvedFromTheArtifactAtRuntime() {
        for (Class<?> type : List.of(FacetKey.class, FacetResolver.class, BaseService.class,
                ServiceLocator.class, CanonicalEntityService.class, EntityDataAccessResolver.class,
                FetchPlanRegistry.class, MetadataResolver.class, SectionMetadataRegistry.class,
                GlobalSearchService.class, GlobalSearchCatalog.class, FilterConditionCodec.class,
                CurrentUser.class)) {
            CodeSource codeSource = type.getProtectionDomain().getCodeSource();
            assertThat(codeSource).as("%s должен грузиться из артефакта", type.getName()).isNotNull();
            assertThat(codeSource.getLocation().toString())
                .as("%s: класс обязан приходить из platform-core, а не из target/classes",
                    type.getName())
                .contains("platform-core");
        }
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

    private static List<Path> javaSources() {
        Path sources = MODULE.resolve("src/main/java");
        if (!Files.isDirectory(sources)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(sources)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String typeOf(Path source) {
        String name = source.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }

    private static String packageOf(Path source) {
        Matcher matcher = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE)
            .matcher(read(source));
        if (!matcher.find()) {
            throw new IllegalStateException("Нет package в " + source);
        }
        return matcher.group(1);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
