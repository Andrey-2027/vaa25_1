package org.ipro;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * П3 «Триада вместе или никак» — ветка «мёртвая аннотация/API»
 * (docs/platform-forms-rules.md §2/§5): элемент объявленной поверхности платформы
 * обязан иметь хотя бы одного production-потребителя.
 *
 * <p><b>Скоуп — осознанное решение.</b> Правило применяется к управляемым правилами
 * поверхностям: {@code org.ipro.form..} (платформа форм) и
 * {@code org.ipro.metadata.annotation..} (декларации сущностей — ветка «мёртвая
 * аннотация». Остальные модули
 * ({@code filtergrid.projection.*}, {@code telemetry.core.*}, …) полны публичных
 * по синтаксу классов, являющихся внутренним устройством модуля: требовать
 * внешних потребителей от них сегодня — заваливать whitelist шумом. Расширение
 * скоупа на модуль — отдельное решение с триажем его публичных классов.</p>
 *
 * <p>Семантика потребления:</p>
 * <ul>
 *   <li>bytecode-зависимость ({@code getDirectDependenciesFromSelf()} — ловит импорты,
 *       наследование, сигнатуры, аннотации);</li>
 *   <li>потребитель — любой production-класс, кроме самого типа и его nest-mates
 *       (внутренние {@code $1}/вложенные классы не «оживляют» тип; внутри-пакетная
 *       сборка платформы — настоящий потребитель: {@code FieldFactory} →
 *       {@code SelectionFormFactory} оживляет фабрику выбора);</li>
 *   <li>SPI, реализуемый приложением, считается потребляемым: production-классы
 *       {@code org.ip..} включены в область анализа (направление границы
 *       {@code org.ipro ↛ org.ip} это правило не проверяет и не нарушает);</li>
 *   <li>тесты потребителями не считаются ({@code DoNotIncludeTests}).</li>
 * </ul>
 *
 * <p>Whitelist для намеренно мертвой/резервной поверхности допустим — каждая строка
 * обязана нести комментарий «почему мертво» (это фиксация вместо забвения).
 * Константа {@link #MIN_WHITELIST} следит, чтобы whitelist не рассыпался тихо:
 * оживил запись — удали строку whitelist'а и уменьши константу одним решением.</p>
 */
class FormDeadApiTest {

    /** Only the reviewed, pre-transition declarations may remain; new application/example code is strict. */
    @Test
    void noNewLegacyFormDeclarations() throws Exception {
        var actual = new java.util.TreeSet<String>();
        var files = new java.util.ArrayList<java.nio.file.Path>();
        for (var root : java.util.List.of(java.nio.file.Path.of("src/main/java"), java.nio.file.Path.of("examples"))) {
            try (var walk = java.nio.file.Files.walk(root)) {
                files.addAll(walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                    .filter(p -> !root.endsWith("examples") || p.toString().replace('\\', '/').contains("/src/main/java/"))
                    .toList());
            }
        }
        var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, java.nio.charset.StandardCharsets.UTF_8)) {
            var task = (com.sun.source.util.JavacTask) compiler.getTask(null, manager, null,
                java.util.List.of("-proc:none"), null, manager.getJavaFileObjectsFromPaths(files));
            for (var unit : task.parse()) {
                String path = java.nio.file.Path.of("").toAbsolutePath().relativize(java.nio.file.Path.of(unit.getSourceFile().toUri()))
                    .toString().replace('\\', '/');
                new com.sun.source.util.TreeScanner<Void, Void>() {
                    String type = "", field = "";
                    @Override public Void visitClass(com.sun.source.tree.ClassTree node, Void ignored) {
                        String previous = type; type = previous + "." + node.getSimpleName();
                        super.visitClass(node, ignored); type = previous; return null;
                    }
                    @Override public Void visitVariable(com.sun.source.tree.VariableTree node, Void ignored) {
                        String previous = field; field = node.getName().toString();
                        super.visitVariable(node, ignored); field = previous; return null;
                    }
                    @Override public Void visitAnnotation(com.sun.source.tree.AnnotationTree node, Void ignored) {
                        String name = node.getAnnotationType().toString().replaceAll(".*\\.", "");
                        if (name.equals("GridColumn")) add("@GridColumn");
                        for (var argument : node.getArguments()) {
                            String key = argument instanceof com.sun.source.tree.AssignmentTree pair ? pair.getVariable().toString() : "value";
                            if (name.equals("EntityMetadata") && Set.of("listColumns", "selectColumns").contains(key)
                                    || name.equals("FieldMetadata") && Set.of("grid", "readOnly").contains(key)
                                    || name.equals("Lookup") && Set.of("entity", "variant", "fetch").contains(key)) add("@" + name + "." + key);
                        }
                        return super.visitAnnotation(node, ignored);
                    }
                    void add(String declaration) { actual.add(path + "#" + type + "#" + field + " " + declaration); }
                }.scan(unit, null);
            }
        }
        var baseline = java.nio.file.Path.of("src/test/resources/forms/legacy-form-declarations.txt");
        if (Boolean.getBoolean("forms.legacy.baseline.write")) {
            java.nio.file.Files.writeString(baseline, "# Reviewed compatibility declarations before AF7.2; new declarations are forbidden.\n"
                + String.join("\n", actual) + "\n");
        }
        var reviewed = java.nio.file.Files.readAllLines(baseline).stream().filter(s -> !s.startsWith("#") && !s.isBlank()).toList();
        assertThat(actual).as("new code must put settings in forms; captured declarations remain compatibility input").isSubsetOf(reviewed);
        assertThat(actual).noneMatch(s -> s.startsWith("examples/"));
    }

    /**
     * Ожидаемое число записей whitelist'а. Меняется только осознанно —
     * сознательная помеха тихому списку.
     */
    private static final int MIN_WHITELIST = 1;

    /**
     * Намеренно мертвое/резервное — каждая запись с обоснованием.
     * Формат: FQN класса, несущего мертвый элемент → «почему мертво/зарезервировано».
     */
    private static final Map<String, String> WHITELIST = new LinkedHashMap<>(Map.of(
            // Реально мертв сегодня (правило это подтвердило при первом прогоне):
            // приложение пользуется Map-перегрузкой setSelectionFilter, record никто
            // не строит. Оживет при схлопывании трех setSelection* перегрузок (Этап 5.4
            // плана). Держим с обязательством: оживить или удалить вместе с Этапом 5.4.
            "org.ipro.form.registry.SelectionFilter",
            "резерв Этапа 5.4 (схлопывание setSelection* перегрузок) — оживить или удалить"
            // E1.7: запись E1.1 про переходный адаптер легаси-команд снята вместе с самим
            // адаптером — обязательство «оживить в E1.6 или удалить вместе с ним» исполнено
            // удалением: миграцию на действия закончил E1.6, а потребителя у адаптера так
            // и не появилось.
    ));

    private static final String[] API_PACKAGES = {
            "org.ipro.form",
            "org.ipro.metadata.annotation"
    };

    @Test
    void everyDeclaredPlatformApiHasAProductionConsumer() {
        Set<JavaClass> classes = new HashSet<>(
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("org.ipro", "org.ip"));

        Set<String> apiFqns = new HashSet<>();
        for (JavaClass c : classes) {
            for (String apiPackage : API_PACKAGES) {
                boolean inApiPackage = c.getPackageName().equals(apiPackage)
                        || c.getPackageName().startsWith(apiPackage + ".");
                if (!inApiPackage) {
                    continue;
                }
                String name = c.getName();
                // Nest-mates (вложенные/анонимные) — реализация, а не объявленная поверхность;
                // AutoConfiguration — точка входа, Spring подключает ее строкой FQN из
                // META-INF imports (та же природа строковой связки, что в pointcut из
                // javadoc PlatformArchitectureTest), bytecode-потребителя у нее быть не должно.
                if (name.contains("$") || name.endsWith("AutoConfiguration")) {
                    continue;
                }
                apiFqns.add(name);
            }
        }
        assertThat(apiFqns).as("API-классы импортированы").isNotEmpty();

        // Один проход по потребителям: target FQN -> пакеты-потребители.
        Map<String, Set<String>> consumerClassesByTarget = new HashMap<>();
        for (JavaClass consumer : classes) {
            consumer.getDirectDependenciesFromSelf().stream()
                    .map(d -> d.getTargetClass().getName())
                    .forEach(target -> consumerClassesByTarget
                            .computeIfAbsent(target, k -> new HashSet<>())
                            .add(consumer.getName()));
        }

        Set<String> dead = new java.util.TreeSet<>();
        for (String fqn : apiFqns) {
            if (WHITELIST.containsKey(fqn)) {
                continue;
            }
            boolean consumed = consumerClassesByTarget
                    .getOrDefault(fqn, Set.of()).stream()
                    // nest-mates (сам тип, вложенные и анонимные классы) не потребители
                    .anyMatch(consumer -> !consumer.equals(fqn)
                            && !consumer.startsWith(fqn + "$"));
            if (!consumed) {
                dead.add(fqn);
            }
        }

        assertThat(WHITELIST)
                .as("whitelist резолвится в объявлениях (не рассыпался тихо)")
                .hasSizeGreaterThanOrEqualTo(MIN_WHITELIST);

        assertThat(dead)
                .as("Мёртвый API платформы (П3, docs/platform-forms-rules.md §5). "
                        + "Оживи потребителем, удали API или добавь строку в WHITELIST "
                        + "с обоснованием «почему мертво».")
                .isEmpty();
    }

    /**
     * Аннотации сущности, атрибуты которых проверяются на потребителя (AF1.5): атрибут, который
     * никто в production-коде не читает, — мёртвая настройка, она обещает поведение, которого нет
     * (так жили {@code sortable}, {@code placeholder} и {@code @Lookup.columns} до AF1).
     */
    private static final String[] ATTRIBUTE_ANNOTATIONS = {
            "org.ipro.metadata.annotation.EntityMetadata",
            "org.ipro.metadata.annotation.FieldMetadata",
            "org.ipro.metadata.annotation.GridColumn",
            "org.ipro.metadata.annotation.Lookup",
            "org.ipro.metadata.annotation.RlsLabels",
            "org.ipro.metadata.annotation.TableSectionMetadata"
    };

    /**
     * Атрибут без читателя, оставленный намеренно: {@code Аннотация.атрибут} → почему.
     * Каждая запись обязана нести причину; пустой список — норма.
     */
    private static final Map<String, String> ATTRIBUTE_WHITELIST = Map.of();

    @Test
    void everyAnnotationAttributeHasAProductionReader() {
        Set<JavaClass> classes = new HashSet<>(
                new ClassFileImporter()
                        .withImportOption(new ImportOption.DoNotIncludeTests())
                        .importPackages("org.ipro", "org.ip"));
        Map<String, JavaClass> byName = new HashMap<>();
        for (JavaClass c : classes) {
            byName.put(c.getName(), c);
        }

        Set<String> unread = new java.util.TreeSet<>();
        int attributes = 0;
        for (String annotationName : ATTRIBUTE_ANNOTATIONS) {
            JavaClass annotation = byName.get(annotationName);
            assertThat(annotation).as("аннотация импортирована: " + annotationName).isNotNull();
            for (var attribute : annotation.getMethods()) {
                attributes++;
                String key = annotation.getSimpleName() + "." + attribute.getName();
                if (ATTRIBUTE_WHITELIST.containsKey(key)) {
                    continue;
                }
                // Читатель — любой production-класс, кроме самой аннотации и её вложенных типов.
                boolean read = attribute.getCallsOfSelf().stream()
                        .map(call -> call.getOriginOwner().getName())
                        .anyMatch(owner -> !owner.equals(annotationName)
                                && !owner.startsWith(annotationName + "$"));
                if (!read) {
                    unread.add(key);
                }
            }
        }

        assertThat(attributes).as("проверка не вакуумная: атрибуты аннотаций найдены")
                .isGreaterThan(25);
        assertThat(unread)
                .as("Атрибут аннотации без production-читателя (AF1.5). Оживи читателем, "
                        + "удали атрибут или добавь запись в ATTRIBUTE_WHITELIST с причиной.")
                .isEmpty();
    }
}
