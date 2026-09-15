package org.ipro;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2 → D3: сколько каждая подсистема платформы ещё тянет из дерева приложения.
 *
 * <p>Срез persistence показал правило, которое решает, какие подсистемы можно выносить
 * дальше: модуль <b>не может</b> ссылаться на дерево — приложение зависит от модуля, и
 * обратная ссылка замкнула бы цикл сборки (поэтому {@code BaseEntity} пришлось везти
 * вместе с сущностью). Значит, пригодность подсистемы к выносу измеряется не числом её
 * прямых импортов, а <b>замыканием</b>: типами дерева, достижимыми транзитивно от пакета
 * подсистемы. Тип, у которого уже есть дом в платформенном артефакте, в дереве
 * отсутствует и в замыкание не попадает — поэтому реестр сам показывает, что осталось.</p>
 *
 * <p>Этот замер был сделан вручную перед выбором следующего среза и он же задал порядок
 * работ: {@code numbering} упирается в один тип дерева, {@code settings} — в два, а
 * {@code reportstudio} — в сорок два; подсистемы выносятся по возрастанию замыкания, а не
 * по размеру. Замер обязан оставаться проверкой, иначе он устареет так же, как устаревали
 * прозаические статусы: тест считает замыкание из исходников и требует совпадения с
 * reviewed-реестром до последнего типа.</p>
 *
 * <p><b>Чем мерить.</b> Первая версия читала только {@code import}, и это занижало
 * замыкание двумя способами: ссылка полным именем в теле (а конфигурации платформы пишут
 * именно так — {@code org.ipro.rls.RlsBypassAudit} в возвращаемом типе бина) была не видна
 * вовсе, а у посещённого типа дерева не просматривались его собственные однопачечные
 * зависимости (файл не импортирует то, что лежит в его же пакете). Из-за второго
 * {@code telemetry} выглядела как «один тип до независимости», хотя тянула пакет RLS
 * целиком. Теперь ссылкой считается импорт, полное имя вне строкового литерала и простое
 * имя, разрешающееся в тип того же пакета.</p>
 *
 * <p>Реестр shrink-only в обе стороны: рост поймает новый импорт подсистемы в дерево,
 * сокращение — состоявшийся срез, запись о котором надо снять сознательно, а не оставить
 * «висящую» цифру в документе. Пустое множество — не «нет данных», а измеренный факт:
 * подсистема готова стать артефактом.</p>
 */
class PlatformSubsystemClosureTest {

    /** Дерево приложения: типы отсюда и есть «тяга», которую надо обнулить. */
    private static final Path APPLICATION_SOURCES = Path.of("src/main/java");

    /**
     * Reviewed-реестр: пакет подсистемы → типы дерева, достижимые транзитивно вне её самой.
     * Порядок записей — порядок выноса: следующая подсистема берётся та, у которой замыкание
     * меньше.
     */
    private static final Map<String, Set<String>> REVIEWED_REACH_BACK = reviewedReachBack();

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile(
        "^\\s*import\\s+(?:static\\s+)?(org\\.ipro\\.[\\w.]+)\\s*;", Pattern.MULTILINE);
    /** Полное имя в теле: возвращаемый тип, вызов статики, ссылка через владельца. */
    private static final Pattern FQN = Pattern.compile("\\borg\\.ipro\\.[A-Za-z0-9_.]+");
    /** Простое имя типа: разрешается в тип того же пакета, что и ссылающийся файл. */
    private static final Pattern SIMPLE_TYPE = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\b");
    /** Строковые литералы из тела убираются: имя в строке — это не ссылка на тип. */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");

    @Test
    void everySubsystemReachBackIntoTheTreeMatchesTheReviewedRegistry() {
        Map<String, Path> tree = treeTypes();

        Map<String, Set<String>> measured = new TreeMap<>();
        for (String subsystem : REVIEWED_REACH_BACK.keySet()) {
            measured.put(subsystem, reachBack(subsystem, tree));
        }

        assertThat(measured)
            .as("замыкание подсистемы на дерево — это работа, которую надо сделать до того,"
                + " как подсистема станет модулем: рост реестра означает новый импорт"
                + " платформенной подсистемы в приложение, сокращение — состоявшийся срез")
            .isEqualTo(REVIEWED_REACH_BACK);
    }

    @Test
    void everyReviewedSubsystemStillExistsAsAPackage() {
        Set<String> tree = treeTypes().keySet();
        List<String> missing = new ArrayList<>();
        for (String subsystem : REVIEWED_REACH_BACK.keySet()) {
            if (tree.stream().noneMatch(type -> type.startsWith(subsystem + "."))) {
                missing.add(subsystem);
            }
        }

        assertThat(missing)
            .as("подсистема, вынесенная в артефакт целиком, должна исчезнуть из этого"
                + " реестра вместе со своей записью — иначе замер описывает то, чего нет")
            .isEmpty();
    }

    private static Set<String> reachBack(String subsystem, Map<String, Path> tree) {
        Deque<String> frontier = new ArrayDeque<>();
        tree.keySet().stream()
            .filter(type -> type.startsWith(subsystem + "."))
            .forEach(frontier::add);

        Set<String> seen = new TreeSet<>();
        while (!frontier.isEmpty()) {
            String type = frontier.poll();
            if (!seen.add(type)) {
                continue;
            }
            for (String reference : referencesOf(tree.get(type))) {
                if (tree.containsKey(reference)) {
                    frontier.add(reference);
                    continue;
                }
                // ссылка на вложенный тип (org.ipro.a.Outer.Inner) или на член статики
                // (org.ipro.a.Outer.MEMBER): владелец — предыдущий сегмент
                String owner = reference.substring(0, reference.lastIndexOf('.'));
                if (tree.containsKey(owner)) {
                    frontier.add(owner);
                }
            }
        }

        Set<String> reachBack = new TreeSet<>();
        for (String type : seen) {
            if (!type.startsWith(subsystem + ".")) {
                reachBack.add(type);
            }
        }
        return reachBack;
    }

    private static Map<String, Path> treeTypes() {
        Map<String, Path> types = new TreeMap<>();
        if (!Files.isDirectory(APPLICATION_SOURCES)) {
            return types;
        }
        try (Stream<Path> files = Files.walk(APPLICATION_SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = withoutComments(read(file));
                Matcher packageMatcher = PACKAGE.matcher(source);
                if (!packageMatcher.find()) {
                    continue;
                }
                String name = file.getFileName().toString();
                types.put(packageMatcher.group(1) + "."
                    + name.substring(0, name.length() - ".java".length()), file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return types;
    }

    /**
     * Типы, на которые ссылается файл: импорты, полные имена в теле (вне строковых литералов)
     * и простые имена, разрешимые в тип своего пакета.
     */
    private static List<String> referencesOf(Path source) {
        String text = withoutComments(read(source));
        List<String> references = new ArrayList<>();

        Matcher imports = IMPORT.matcher(text);
        while (imports.find()) {
            references.add(imports.group(1));
        }

        String body = STRING_LITERAL.matcher(text).replaceAll("\"\"");
        Matcher fqns = FQN.matcher(body);
        while (fqns.find()) {
            references.add(fqns.group());
        }

        Matcher packageMatcher = PACKAGE.matcher(text);
        if (packageMatcher.find()) {
            String pkg = packageMatcher.group(1);
            Matcher simpleTypes = SIMPLE_TYPE.matcher(body);
            while (simpleTypes.find()) {
                references.add(pkg + "." + simpleTypes.group(1));
            }
        }
        return references;
    }

    /** Импорт без комментариев: упоминание {@code org.ipro...} в javadoc зависимостью не является. */
    private static String withoutComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll(" ")).replaceAll(" ");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Set<String>> reviewedReachBack() {
        Map<String, Set<String>> registry = new TreeMap<>();

        // Замыкание пусто: связи цикла вывернуты через нейтральные швы телеметрии
        // (SqlStatementAudit, DeclaredNameSource), поэтому подсистема наблюдения может стать
        // артефактом. Единственный артефакт с пустым замыканием — следующий срез.
        registry.put("org.ipro.telemetry", Set.of());

        registry.put("org.ipro.rls", Set.of(
            "org.ipro.crud.StandardCatalogEntity",
            "org.ipro.crud.StandardDocumentEntity",
            "org.ipro.crud.TableSectionService",
            "org.ipro.fetch.instance.InstanceName",
            "org.ipro.fetch.instance.InstanceNameBridge",
            "org.ipro.fetch.instance.InstanceNameProvider",
            "org.ipro.fetch.instance.InstanceNameResolver",
            "org.ipro.metadata.ColumnPath",
            "org.ipro.metadata.EntityMetadataInfo",
            "org.ipro.metadata.FactOrigin",
            "org.ipro.metadata.FieldMetadataInfo",
            "org.ipro.metadata.FilterSpec",
            "org.ipro.metadata.GridMetadata",
            "org.ipro.metadata.GridViewState",
            "org.ipro.metadata.HasDisplayName",
            "org.ipro.metadata.MetadataCache",
            "org.ipro.metadata.MetadataDiagnostic",
            "org.ipro.metadata.MetadataDiagnosticCodes",
            "org.ipro.metadata.MetadataResolver",
            "org.ipro.metadata.RowMetadataInfo",
            "org.ipro.metadata.SectionMetadataRegistry",
            "org.ipro.metadata.TableSectionMetadataInfo",
            "org.ipro.telemetry.api.AggregateStats",
            "org.ipro.telemetry.api.EventSink",
            "org.ipro.telemetry.api.EventType",
            "org.ipro.telemetry.api.FieldChangeRecord",
            "org.ipro.telemetry.api.SqlStatementAudit",
            "org.ipro.telemetry.api.TelemetryEvent",
            "org.ipro.telemetry.core.PayloadJson",
            "org.ipro.telemetry.core.SecurityEventLogger",
            "org.ipro.telemetry.core.SqlStatementAuditBridge"));

        registry.put("org.ipro.reportstudio", Set.of(
            "org.ipro.crud.BaseService",
            "org.ipro.crud.LookupService",
            "org.ipro.crud.ReferenceCheckService",
            "org.ipro.crud.ServiceLocator",
            "org.ipro.crud.StandardCatalogEntity",
            "org.ipro.crud.StandardDocumentEntity",
            "org.ipro.crud.TableSectionService",
            "org.ipro.crud.ValidationException",
            "org.ipro.crud.jpa.ValidatedJpaCrudService",
            "org.ipro.data.CanonicalEntityService",
            "org.ipro.data.CanonicalReadExecutor",
            "org.ipro.data.DetailRead",
            "org.ipro.data.EntityCapabilities",
            "org.ipro.data.EntityDataAccess",
            "org.ipro.data.EntityDataAccessResolver",
            "org.ipro.data.EntityDataPolicy",
            "org.ipro.data.EntityDescriptor",
            "org.ipro.data.EntityDescriptorCatalog",
            "org.ipro.data.ListRead",
            "org.ipro.data.LookupRead",
            "org.ipro.data.PageRead",
            "org.ipro.data.ReadTelemetry",
            "org.ipro.data.ScenarioFetchGraphResolver",
            "org.ipro.data.SearchContext",
            "org.ipro.data.SearchFieldResolver",
            "org.ipro.data.SearchRead",
            "org.ipro.data.SearchTerms",
            "org.ipro.fetch.ManagedEntityTypes",
            "org.ipro.fetch.instance.InstanceName",
            "org.ipro.fetch.instance.InstanceNameBridge",
            "org.ipro.fetch.instance.InstanceNameProvider",
            "org.ipro.fetch.instance.InstanceNameResolver",
            "org.ipro.fetch.plan.FetchPlan",
            "org.ipro.fetch.plan.FetchPlanRegistry",
            "org.ipro.form.EntityField",
            "org.ipro.form.FieldRenderer",
            "org.ipro.form.FilterGridMoreMenu",
            "org.ipro.form.SearchFunction",
            "org.ipro.form.SelectionForm",
            "org.ipro.form.SelectionFormAssembler",
            "org.ipro.form.SelectionFormFactory",
            "org.ipro.form.SelectionGridCustomizer",
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
            "org.ipro.metadata.MetadataCache",
            "org.ipro.metadata.MetadataDiagnostic",
            "org.ipro.metadata.MetadataDiagnosticCodes",
            "org.ipro.metadata.MetadataResolver",
            "org.ipro.metadata.RowMetadataInfo",
            "org.ipro.metadata.SectionMetadataRegistry",
            "org.ipro.metadata.TableSectionMetadataInfo",
            "org.ipro.rls.AccessGrant",
            "org.ipro.rls.AccessGrantChangeListener",
            "org.ipro.rls.AccessGrantRepository",
            "org.ipro.rls.AccessGrantVersion",
            "org.ipro.rls.AccessService",
            "org.ipro.rls.RlsAccessDeniedException",
            "org.ipro.rls.RlsBypassAudit",
            "org.ipro.rls.RlsBypassScope",
            "org.ipro.rls.RlsCheckValue",
            "org.ipro.rls.RlsContext",
            "org.ipro.rls.RlsCurrentUser",
            "org.ipro.rls.RlsDimension",
            "org.ipro.rls.RlsDimensionKind",
            "org.ipro.rls.RlsDimensionRegistry",
            "org.ipro.rls.RlsDimensionValue",
            "org.ipro.rls.RlsDimensions",
            "org.ipro.rls.RlsFilterActivator",
            "org.ipro.rls.RlsPolicyDescriptor",
            "org.ipro.rls.RlsPolicyEnforcer",
            "org.ipro.rls.RlsReadGate",
            "org.ipro.rls.RlsReadableIdsCache",
            "org.ipro.rls.RlsRoleResolver",
            "org.ipro.rls.RlsStatementGuard",
            "org.ipro.rls.RlsWriteAuthorization",
            "org.ipro.security.CurrentUser",
            "org.ipro.telemetry.api.AggregateStats",
            "org.ipro.telemetry.api.EventSink",
            "org.ipro.telemetry.api.EventType",
            "org.ipro.telemetry.api.FieldChangeRecord",
            "org.ipro.telemetry.api.SqlStatementAudit",
            "org.ipro.telemetry.api.TelemetryEvent",
            "org.ipro.telemetry.core.PayloadJson",
            "org.ipro.telemetry.core.SecurityEventLogger"));

        return registry;
    }
}
