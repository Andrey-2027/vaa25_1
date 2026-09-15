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
import java.util.LinkedHashSet;
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
 * <p>Реестр shrink-only в обе стороны: рост поймает новый импорт подсистемы в дерево,
 * сокращение — состоявшийся срез, запись о котором надо снять сознательно, а не оставить
 * «висящую» цифру в документе.</p>
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
            for (String imported : importsOf(tree.get(type))) {
                if (tree.containsKey(imported)) {
                    frontier.add(imported);
                    continue;
                }
                // импорт вложенного типа (org.ipro.a.Outer.Inner) или статический импорт
                // члена (org.ipro.a.Outer.MEMBER): владелец — предыдущий сегмент
                String owner = imported.substring(0, imported.lastIndexOf('.'));
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

    private static List<String> importsOf(Path source) {
        List<String> imports = new ArrayList<>();
        Matcher matcher = IMPORT.matcher(withoutComments(read(source)));
        while (matcher.find()) {
            imports.add(matcher.group(1));
        }
        return imports;
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
        registry.put("org.ipro.telemetry", Set.of("org.ipro.rls.RlsStatementGuard"));
        registry.put("org.ipro.rls", new LinkedHashSet<>(List.of(
            "org.ipro.crud.StandardCatalogEntity",
            "org.ipro.crud.StandardDocumentEntity",
            "org.ipro.crud.TableSectionService",
            "org.ipro.metadata.ColumnPath",
            "org.ipro.metadata.EntityMetadataInfo",
            "org.ipro.metadata.HasDisplayName",
            "org.ipro.metadata.MetadataResolver",
            "org.ipro.metadata.SectionMetadataRegistry",
            "org.ipro.metadata.TableSectionMetadataInfo",
            "org.ipro.telemetry.api.EventSink",
            "org.ipro.telemetry.api.EventType",
            "org.ipro.telemetry.api.TelemetryEvent",
            "org.ipro.telemetry.core.SecurityEventLogger")));
        registry.put("org.ipro.reportstudio", new LinkedHashSet<>(List.of(
            "org.ipro.crud.BaseService",
            "org.ipro.crud.LookupService",
            "org.ipro.crud.ReferenceCheckService",
            "org.ipro.crud.ServiceLocator",
            "org.ipro.crud.StandardCatalogEntity",
            "org.ipro.crud.StandardDocumentEntity",
            "org.ipro.crud.TableSectionService",
            "org.ipro.crud.ValidationException",
            "org.ipro.crud.jpa.ValidatedJpaCrudService",
            "org.ipro.data.CanonicalReadExecutor",
            "org.ipro.data.DetailRead",
            "org.ipro.data.ListRead",
            "org.ipro.data.LookupRead",
            "org.ipro.fetch.instance.InstanceNameBridge",
            "org.ipro.fetch.instance.InstanceNameResolver",
            "org.ipro.form.EntityField",
            "org.ipro.form.SelectionForm",
            "org.ipro.form.SelectionFormAssembler",
            "org.ipro.metadata.ColumnPath",
            "org.ipro.metadata.EntityMetadataInfo",
            "org.ipro.metadata.FieldMetadataInfo",
            "org.ipro.metadata.HasDisplayName",
            "org.ipro.metadata.MetadataResolver",
            "org.ipro.metadata.SectionMetadataRegistry",
            "org.ipro.metadata.TableSectionMetadataInfo",
            "org.ipro.rls.AccessService",
            "org.ipro.rls.RlsAccessDeniedException",
            "org.ipro.rls.RlsContext",
            "org.ipro.rls.RlsCurrentUser",
            "org.ipro.rls.RlsDimensionRegistry",
            "org.ipro.rls.RlsFilterActivator",
            "org.ipro.rls.RlsPolicyEnforcer",
            "org.ipro.rls.RlsReadGate",
            "org.ipro.security.CurrentUser",
            "org.ipro.telemetry.api.EventSink",
            "org.ipro.telemetry.api.EventType",
            "org.ipro.telemetry.api.TelemetryEvent",
            "org.ipro.telemetry.core.SecurityEventLogger")));
        return registry;
    }
}
