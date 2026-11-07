package org.ip.form;

import org.ip.config.DataInitializer;
import org.ipro.data.SearchFieldResolver;
import org.ipro.fetch.instance.InstanceNameBridge;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.fetch.plan.FetchPlanRegistry;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.form.LookupComboHelper;
import org.ipro.form.builder.ContextFilterPanel;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.FieldMetadataInfo;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionGridMetadata;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.rls.RlsDimensionValueLabelResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * П.2 плана «Настройки форм вне сущности и расширяемая автоформа»: снимок настроек форм «как
 * есть». Это эталон для проверки того, что этапы AF1–AF5 не меняют поведение там, где не должны.
 *
 * <p>По каждой управляемой сущности с метаданными в один текстовый снимок записываются колонки
 * списка и выбора (с подписью, типом, шириной и {@code flexGrow}), поля карточки, колонки строк
 * табличных частей, поля серверного поиска, поля автокомплита по трём путям
 * ({@code FieldFactory}, {@code ContextFilterPanel}, {@code LookupComboHelper}), текст
 * представления на тестовом экземпляре, пара «код — название» для подписей RLS и пути планов
 * LIST, DETAIL, LOOKUP и ROW.</p>
 *
 * <p>Тестовый экземпляр заполняется детерминированно: строки — путём поля, числа — константами,
 * ссылки — вложенными экземплярами до глубины {@value #SAMPLE_DEPTH}.</p>
 *
 * <p>Эталон обновляется осознанно: {@code -Dform.settings.snapshot.write=true} пишет текущий
 * снимок в {@code target/form-settings-current.txt} для ручного переноса в ресурс.</p>
 */
@SpringBootTest(classes = org.ip.Application.class)
class FormSettingsSnapshotTest {

    private static final Path WRITE_TARGET = Path.of("target", "form-settings-current.txt");
    private static final int SAMPLE_DEPTH = 2;

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private ManagedEntityCatalog managedEntityCatalog;

    @Autowired
    private MetadataResolver metadataResolver;

    @Autowired
    private InstanceNameResolver instanceNameResolver;

    @Autowired
    private SectionMetadataRegistry sectionMetadataRegistry;

    @Autowired
    private FetchPlanRegistry fetchPlanRegistry;

    @Autowired
    private ObjectProvider<RlsDimensionValueLabelResolver> rlsLabelResolver;

    @Test
    void writeCurrentSnapshotWhenRequested() throws IOException {
        if (!Boolean.getBoolean("form.settings.snapshot.write")) {
            return;
        }
        Files.createDirectories(WRITE_TARGET.getParent());
        Files.writeString(WRITE_TARGET, currentSnapshot(), StandardCharsets.UTF_8);
        System.out.println("[form-settings-snapshot] written to " + WRITE_TARGET);
    }

    @Test
    void formSettingsMatchTheCheckedInSnapshot() {
        assertThat(currentSnapshot())
            .as("настройки форм изменились: обновите эталон осознанно "
                + "(-Dform.settings.snapshot.write=true)")
            .isEqualTo(resource("/forms/form-settings-snapshot.txt"));
    }

    @Test
    void snapshotIsDeterministicWithinOneRun() {
        assertThat(currentSnapshot()).isEqualTo(currentSnapshot());
    }

    @Test
    void resolvedColumnsKeepTheirReferencePresentationDependencies() {
        for (Class<?> type : managedEntityCatalog.managedEntityClasses()) {
            metadataResolver.find(type).ifPresent(meta -> {
                assertColumnFetchPaths(type, FetchScenario.LIST, meta.getListColumnPaths());
                for (TableSectionMetadataInfo section : sectionMetadataRegistry.forOwner(type)) {
                    assertColumnFetchPaths(section.getRowClass(), FetchScenario.ROW,
                        new TableSectionGridMetadata(section).getListColumnPaths());
                }
            });
        }
    }

    private void assertColumnFetchPaths(Class<?> type, FetchScenario scenario, List<ColumnPath> columns) {
        var extras = columns.stream().flatMap(column -> column.getFetchPaths().stream()).toList();
        var paths = fetchPlanRegistry.pathsWith(type, scenario, extras);
        for (ColumnPath column : columns) {
            assertThat(paths).as("%s %s column %s", type.getName(), scenario, column.getKey())
                .containsAll(column.getFetchPaths());
            if (column.getResolvedType() == FieldType.ENTITY_REFERENCE) {
                var presentation = new org.ipro.data.PresentationResolver(metadataResolver, instanceNameResolver);
                for (String dependency : presentation.displayPaths(column.getJavaType())) {
                    var targetPath = ColumnPath.resolve(column.getJavaType(), dependency);
                    for (String association : targetPath.getFetchPaths()) {
                        assertThat(paths).as("%s %s presentation of %s", type.getName(), scenario, column.getKey())
                            .contains(column.getKey() + "." + association);
                    }
                }
            }
        }
    }

    private String currentSnapshot() {
        SearchFieldResolver searchFields =
            new SearchFieldResolver(metadataResolver, instanceNameResolver);
        List<Class<?>> entities = managedEntityCatalog.managedEntityClasses().stream()
            .sorted(Comparator.comparing(Class::getName))
            .filter(type -> metadataResolver.find(type).isPresent())
            .collect(Collectors.toList());

        StringBuilder out = new StringBuilder();
        out.append("# form-settings snapshot, entities=").append(entities.size()).append('\n');
        for (Class<?> type : entities) {
            EntityMetadataInfo meta = metadataResolver.resolve(type);
            out.append('\n').append("== ").append(type.getName()).append('\n');
            line(out, "titles", () -> "list='" + meta.getListFormTitle()
                + "' item='" + meta.getItemFormTitle()
                + "' selection='" + meta.getSelectionFormTitle() + "'");
            line(out, "listColumns", () -> columns(meta.getListColumnPaths()));
            line(out, "selectColumns", () -> columns(meta.getSelectColumnPaths()));
            line(out, "explicit", () -> "list=" + meta.hasExplicitListColumns()
                + " select=" + meta.hasExplicitSelectColumns());
            line(out, "formFields", () -> meta.getFormFields().stream()
                .map(f -> f.getName() + "{label=" + f.getLabel() + "|order=" + f.getOrder()
                    + "|required=" + f.isRequired() + "|type=" + f.getResolvedType()
                    + "|readOnly=" + f.isReadOnly() + "}")
                .collect(Collectors.joining(", ")));
            tableSections(out, type);
            line(out, "searchFields", () -> String.join(",", searchFields.resolve(type, List.of())));
            line(out, "displaySortFields", () -> String.join(",", meta.getDisplaySortFields()));
            line(out, "instanceName", () -> "declared=" + instanceNameResolver.hasDeclaration(type)
                + " paths=" + String.join(",", instanceNameResolver.instanceNamePaths(type)));
            autocomplete(out, meta);
            Object sample = sampleOf(type);
            line(out, "displayName", () -> sample == null
                ? "<no sample>" : quote(InstanceNameBridge.displayName(sample)));
            line(out, "rlsLabels", () -> {
                RlsDimensionValueLabelResolver rls = rlsLabelResolver.getIfAvailable();
                if (rls == null || sample == null) {
                    return "<n/a>";
                }
                RlsDimensionValueLabelResolver.Labels labels = rls.resolve(type, sample);
                return "code=" + quote(labels.code()) + " name=" + quote(labels.name());
            });
            for (FetchScenario scenario : FetchScenario.values()) {
                line(out, "plan." + scenario,
                    () -> String.join(",", fetchPlanRegistry.paths(type, scenario)));
            }
        }
        // "\n" явно, а не lineSeparator(): эталон переносим между Windows и Linux.
        return out.toString();
    }

    private void tableSections(StringBuilder out, Class<?> owner) {
        List<TableSectionMetadataInfo> sections;
        try {
            sections = sectionMetadataRegistry.forOwner(owner);
        } catch (RuntimeException e) {
            out.append("sections: ERROR ").append(e.getClass().getSimpleName()).append('\n');
            return;
        }
        for (TableSectionMetadataInfo section : sections.stream()
            .sorted(Comparator.comparing(TableSectionMetadataInfo::getKey)).toList()) {
            String prefix = "section." + section.getKey();
            line(out, prefix, () -> "row=" + section.getRowClass().getName()
                + " title='" + section.getTitle() + "' order=" + section.getOrder()
                + " minRows=" + section.getMinRows());
            line(out, prefix + ".columns",
                () -> columns(new TableSectionGridMetadata(section).getListColumnPaths()));
            line(out, prefix + ".formFields", () -> section.getFormFields().stream()
                .map(f -> f.getName() + "{label=" + f.getLabel() + "|order=" + f.getOrder()
                    + "|required=" + f.isRequired() + "}")
                .collect(Collectors.joining(", ")));
            line(out, prefix + ".plan.ROW", () -> String.join(",",
                fetchPlanRegistry.paths(section.getRowClass(), FetchScenario.ROW)));
        }
    }

    /** Поля автокомплита ссылочных полей карточки по трём путям. */
    private void autocomplete(StringBuilder out, EntityMetadataInfo meta) {
        for (FieldMetadataInfo field : meta.getFormFields()) {
            if (!field.hasLookup()) {
                continue;
            }
            Class<?> target = field.getLookupEntity();
            String variant = field.getLookupVariant();
            String prefix = "autocomplete." + field.getName();
            line(out, prefix, () -> "target=" + target.getName() + " variant='" + variant + "'");
            // FieldFactory использует этот же общий вход представления, независимо от варианта колонок.
            line(out, prefix + ".fieldFactory", () -> String.join(",",
                LookupComboHelper.textSearchFields(target, metadataResolver)));
            line(out, prefix + ".contextFilterPanel", () -> {
                ContextFilterPanel panel = new ContextFilterPanel(
                    meta.getEntityClass(), List.of(), metadataResolver, null, null, (k, v) -> { });
                Method method = ContextFilterPanel.class
                    .getDeclaredMethod("searchFieldsOf", Class.class);
                method.setAccessible(true);
                return String.join(",", (String[]) method.invoke(panel, target));
            });
            line(out, prefix + ".lookupComboHelper", () -> String.join(",",
                LookupComboHelper.textSearchFields(target, metadataResolver)));
        }
    }

    // === Форматирование ===

    private static String columns(List<ColumnPath> paths) {
        return paths.stream().map(FormSettingsSnapshotTest::column).collect(Collectors.joining(", "));
    }

    private static String column(ColumnPath path) {
        String width = path.asFieldMetadata().map(FieldMetadataInfo::getGridWidth).orElse("-");
        String flex = path.asFieldMetadata()
            .map(f -> String.valueOf(f.getGridFlexGrow())).orElse("-");
        return path.getKey() + "{label=" + path.getLabel() + "|type=" + path.getResolvedType()
            + "|width=" + (width.isEmpty() ? "-" : width) + "|flexGrow=" + flex + "}";
    }


    private static String quote(String text) {
        return text == null ? "null" : "'" + text.replace("\n", "\\n") + "'";
    }

    /** Строка снимка; сбой чтения фиксируется как значение, а не роняет весь снимок. */
    private static void line(StringBuilder out, String name, ThrowingSupplier value) {
        String text;
        try {
            text = value.get();
        } catch (Exception | LinkageError e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite
                && ite.getCause() != null ? ite.getCause() : e;
            text = "ERROR " + cause.getClass().getSimpleName();
        }
        out.append(name).append(": ").append(text).append('\n');
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        String get() throws Exception;
    }

    // === Тестовый экземпляр ===

    private static Object sampleOf(Class<?> type) {
        return sample(type, "", 0);
    }

    private static Object sample(Class<?> type, String path, int depth) {
        if (type == String.class) {
            return path;
        }
        if (type == Long.class || type == long.class) {
            return 1L;
        }
        if (type == Integer.class || type == int.class) {
            return 1;
        }
        if (type == Short.class || type == short.class) {
            return (short) 1;
        }
        if (type == Double.class || type == double.class) {
            return 1.5d;
        }
        if (type == BigDecimal.class) {
            return new BigDecimal("1.50");
        }
        if (type == Boolean.class || type == boolean.class) {
            return Boolean.TRUE;
        }
        if (type == LocalDate.class) {
            return LocalDate.of(2026, 1, 2);
        }
        if (type == LocalDateTime.class) {
            return LocalDateTime.of(2026, 1, 2, 3, 4);
        }
        if (type == LocalTime.class) {
            return LocalTime.of(3, 4);
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return constants.length == 0 ? null : constants[0];
        }
        if (isEntity(type)) {
            if (depth >= SAMPLE_DEPTH) {
                return null;
            }
            return entitySample(type, path, depth);
        }
        return null;
    }

    private static boolean isEntity(Class<?> type) {
        return type.isAnnotationPresent(jakarta.persistence.Entity.class)
            || type.isAnnotationPresent(jakarta.persistence.MappedSuperclass.class);
    }

    private static Object entitySample(Class<?> type, String path, int depth) {
        if (Modifier.isAbstract(type.getModifiers())) {
            return null;
        }
        Object instance;
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            instance = constructor.newInstance();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            hierarchy.addFirst(c);
        }
        for (Class<?> c : hierarchy) {
            Field[] fields = c.getDeclaredFields();
            Arrays.sort(fields, Comparator.comparing(Field::getName));
            for (Field field : fields) {
                int mods = field.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isTransient(mods)
                    || Collection.class.isAssignableFrom(field.getType())
                    || Map.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                String childPath = path.isEmpty() ? field.getName() : path + "." + field.getName();
                Object value = sample(field.getType(), childPath, depth + 1);
                if (value == null) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    field.set(instance, value);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // поле не заполняется — остаётся значением по умолчанию
                }
            }
        }
        return instance;
    }

    private static String resource(String path) {
        try (InputStream stream = FormSettingsSnapshotTest.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("Отсутствует ресурс снимка " + path
                    + " — сгенерируйте его через -Dform.settings.snapshot.write=true");
            }
            // Windows checkout может хранить CRLF; снимок всегда пишется с LF.
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
