package org.ipro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1/D2 — baseline публичной поверхности артефактов платформы.
 *
 * <p>Reviewed-списки в тестах модулей фиксируют <b>имена</b> типов: они поймают исчезнувший тип,
 * но не изменение подписи. Правка метода {@code EntityLifecycle}, удаление константы enum'а или
 * смена annotation default пройдёт мимо них и мимо архитектурных правил — а для потребителя это
 * ровно тот же слом, что и удаление типа. Разбор D1/D2 указал на этот пробел отдельно:
 * compatibility policy D1 §6.3 обещает «публичная поверхность фиксируется явно», а фиксировала
 * только состав.</p>
 *
 * <p>Здесь фиксируется поверхность: вид типа, модификаторы, generic supertype/interfaces,
 * конструкторы, методы с generic-подписью, поля с константными значениями, константы enum'ов,
 * значения по умолчанию у аннотаций и их {@code @Target}/{@code @Retention}.</p>
 *
 * <p>Сравнение — с baseline-файлом в {@code src/test/resources/platform-api-baseline/}. Обновление
 * намеренное: {@code mvn test -Dplatform.api.baseline.write=true} перезаписывает файл, так что
 * изменение поверхности видно и в диффе, и в отдельной команде.</p>
 *
 * <p>Артефакт, каталог которого отсутствует (например {@code ../crudui} вне этого воркспейса),
 * пропускается: тест проверяет то, что есть, а не падает из-за чужой раскладки.</p>
 */
class PlatformApiBaselineTest {

    private static final String WRITE_PROPERTY = "platform.api.baseline.write";

    private static final Path BASELINE_DIRECTORY = Path.of("src/test/resources/platform-api-baseline");

    private static final Path MANIFEST = Path.of("scripts/local-dependencies.json");

    /** Реестр семантических ролей core: из него берётся, какие типы в baseline не попадают. */
    private static final Path CORE_ROLE_REGISTRY = Path.of("src/test/resources/platform-core-surface.txt");

    /**
     * Артефакты, публикуемые реактором соседнего {@code crudui}. В этом манифесте их нет, потому что
     * их собирает другой манифест, но публикуются они как платформенные и обязаны иметь baseline:
     * перечисление здесь — явное, а не «сколько успели добавить».
     */
    private static final List<String> SIBLING_LEAF_ARTIFACTS =
        List.of("platform-crud-api", "platform-identity-api");

    /**
     * Политика фиксации поверхности артефакта.
     *
     * <p>Первый вариант — не режим по умолчанию, а состояние миграции: до семантической
     * классификации артефакта его public-поверхность приходится фиксировать целиком, включая
     * реализацию. Пометка живёт в шапке baseline-файла, поэтому «временное» видно и в ревью
     * диффа, а не только в этом коде.</p>
     */
    private enum Policy {
        /** Только роли APP_API / APP_SPI / MODULE_API: semantic internal меняется свободно. */
        SEMANTIC_ROLES,
        /** TODO(D3.9): весь public/protected, пока классификации нет. */
        TEMPORARY_ALL_PUBLIC
    }

    /** Артефакт, его исходники и политика фиксации. */
    private record Artifact(String artifactId, Path sourceRoot, Policy policy) {
    }

    @Test
    void artifactPublicSurfaceMatchesItsReviewedBaseline() {
        boolean write = Boolean.getBoolean(WRITE_PROPERTY);
        List<String> problems = new ArrayList<>();
        int verified = 0;

        for (Artifact artifact : artifacts()) {
            if (!Files.isDirectory(artifact.sourceRoot())) {
                continue;
            }
            String actual = describe(artifact);
            Path baseline = BASELINE_DIRECTORY.resolve(artifact.artifactId() + ".api");
            if (write) {
                write(baseline, actual);
                verified++;
                continue;
            }
            assertThat(baseline)
                .as("у артефакта %s нет baseline поверхности: он должен быть зафиксирован"
                    + " намеренно", artifact.artifactId())
                .exists();
            String reviewed = read(baseline);
            if (!reviewed.equals(actual)) {
                problems.add(artifact.artifactId() + ": поверхность разошлась с baseline\n"
                    + firstDifference(reviewed, actual));
            }
            assertThat(reviewed)
                .as("политика фиксации обязана быть записана в самом baseline: иначе временный"
                    + " режим TEMPORARY_ALL_PUBLIC неотличим от осознанно узкой поверхности, и его"
                    + " никто не снимет")
                .contains("policy: " + artifact.policy());
            verified++;
        }

        assertThat(problems)
            .as("изменение публичной подписи — это изменение контракта. Reviewed-списки типов"
                + " такое не ловят: они видят только состав. Если изменение намеренное,"
                + " перезапишите baseline (%s=true) и объясните его в ревью", WRITE_PROPERTY)
            .isEmpty();
        assertThat(verified)
            .as("проверка не должна быть вакуумной: baseline есть у каждого опубликованного"
                + " платформенного артефакта, чьи исходники есть в этой раскладке")
            .isGreaterThanOrEqualTo(8);
    }

    /**
     * Перечень baseline-артефактов связан с манифестом, а не написан руками.
     *
     * <p>Прежний список из пяти артефактов был именно руками — и уже разошёлся с поставкой:
     * metadata, numbering, settings, telemetry, rls и оба leaf-контракта публиковались без
     * фиксации поверхности. Артефакт, попавший в манифест и не попавший в baseline, можно
     * выпустить, не заметив ни одного несовместимого изменения, а гейт об этом не скажет.</p>
     */
    @Test
    void baselineCoversEveryPublishedPlatformArtifactAndNothingElse() {
        Set<String> published = new TreeSet<>();
        for (Artifact artifact : artifacts()) {
            if (Files.isDirectory(artifact.sourceRoot())) {
                published.add(artifact.artifactId());
            }
        }
        Set<String> reviewed = new TreeSet<>();
        for (Path file : baselineFiles()) {
            reviewed.add(file.getFileName().toString().replace(".api", ""));
        }

        Set<String> unbaselined = new TreeSet<>(published);
        unbaselined.removeAll(reviewed);
        Set<String> orphaned = new TreeSet<>(reviewed);
        orphaned.removeAll(published);

        assertThat(unbaselined)
            .as("платформенный артефакт публикуется без baseline: его поверхность можно сломать,"
                + " не заметив этого ни в диффе, ни сборкой")
            .isEmpty();
        assertThat(orphaned)
            .as("baseline описывает артефакт, которого в поставке больше нет: список должен"
                + " совпадать в обе стороны")
            .isEmpty();
    }

    /**
     * Временный режим все-публичности — долг, и он обязан быть виден и сокращаться.
     *
     * <p>Семантическую классификацию прошёл пока только {@code platform-core}. Пока остальные
     * артефакты зафиксированы целиком (TEMPORARY_ALL_PUBLIC), их реализацию нельзя менять
     * свободно — это противоречит политике D1, и D3.9 обязан это снять. Тест превращает
     * «когда-нибудь» в список: он печатает, кто ещё в этом режиме, и падает на новый артефакт,
     * которого никто не классифицировал.</p>
     */
    @Test
    void onlyTheSemanticallyClassifiedArtifactUsesRoleBasedBaseline() {
        List<String> semantic = artifacts().stream()
            .filter(artifact -> artifact.policy() == Policy.SEMANTIC_ROLES)
            .map(Artifact::artifactId)
            .sorted()
            .toList();
        List<String> temporary = artifacts().stream()
            .filter(artifact -> artifact.policy() == Policy.TEMPORARY_ALL_PUBLIC)
            .map(Artifact::artifactId)
            .sorted()
            .toList();

        assertThat(semantic)
            .as("артефакт с семантической классификацией пока один — platform-core: у остальных нет"
                + " реестра ролей, и фиксировать их поверхность по ролям нечем")
            .isEqualTo(List.of("platform-core"));
        assertThat(temporary)
            .as("долг D3.9: эти артефакты всё ещё заморожены целиком, включая реализацию")
            .isNotEmpty();
    }

    /**
     * Поверхность артефакта: его публичные и protected типы (включая вложенные), описанные
     * канонически и в стабильном порядке. Приватные члены в baseline не попадают намеренно:
     * это не контракт, и их переименование не должно требовать правки ревьюируемого файла —
     * иначе baseline быстро перестанут читать.
     */
    private static String describe(Artifact artifact) {
        List<String> descriptions = new ArrayList<>();
        Set<String> excluded = artifact.policy() == Policy.SEMANTIC_ROLES ? internalTypes() : Set.of();
        for (String className : artifactTypeNames(artifact)) {
            if (excluded.contains(className)) {
                continue;
            }
            collectApiTypes(loadClass(className), descriptions);
        }
        descriptions.sort(Comparator.naturalOrder());
        StringBuilder text = new StringBuilder();
        text.append("# ").append(artifact.artifactId()).append(": публичная поверхность")
            .append(" (policy: ").append(artifact.policy())
            .append(", перезапись: -D").append(WRITE_PROPERTY).append("=true)\n");
        descriptions.forEach(description -> text.append(description).append('\n'));
        return text.toString();
    }

    /**
     * Артефакты для проверки: платформенные проекты манифеста плюс leaf-контракты соседнего
     * реактора. Источник — манифест, поэтому новый platform-модуль не может появиться без
     * baseline: его поверхность не зафиксирует никто.
     */
    private static List<Artifact> artifacts() {
        List<Artifact> artifacts = new ArrayList<>();
        for (JsonNode project : readManifest().get("projects")) {
            String relativePath = project.get("relativePath").asText();
            for (JsonNode artifact : project.get("artifacts")) {
                String[] parts = artifact.asText().split(":");
                if (parts.length < 2 || !parts[1].startsWith("platform-")) {
                    continue;
                }
                artifacts.add(new Artifact(parts[1],
                    Path.of(relativePath).resolve("src/main/java"), policyFor(parts[1])));
            }
        }
        for (String artifactId : SIBLING_LEAF_ARTIFACTS) {
            artifacts.add(new Artifact(artifactId,
                Path.of("../crudui").resolve(artifactId).resolve("src/main/java"), policyFor(artifactId)));
        }
        return artifacts;
    }

    private static Policy policyFor(String artifactId) {
        return artifactId.equals("platform-core") ? Policy.SEMANTIC_ROLES : Policy.TEMPORARY_ALL_PUBLIC;
    }

    private static JsonNode readManifest() {
        try {
            return new ObjectMapper().readTree(Files.readString(MANIFEST, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> baselineFiles() {
        if (!Files.isDirectory(BASELINE_DIRECTORY)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(BASELINE_DIRECTORY)) {
            return files.filter(path -> path.toString().endsWith(".api")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Типы core с ролью INTERNAL: их реализация — не контракт, и в signature baseline не входит. */
    private static Set<String> internalTypes() {
        Set<String> internal = new TreeSet<>();
        try {
            for (String line : Files.readAllLines(CORE_ROLE_REGISTRY, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("INTERNAL ")) {
                    internal.add(trimmed.substring("INTERNAL ".length()).trim());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return internal;
    }

    private static void collectApiTypes(Class<?> type, List<String> descriptions) {
        if (!isApiType(type) || type.isSynthetic() || type.isAnonymousClass() || type.isLocalClass()) {
            return;
        }
        descriptions.add(describe(type));
        for (Class<?> nested : type.getDeclaredClasses()) {
            collectApiTypes(nested, descriptions);
        }
    }

    private static boolean isApiType(Class<?> type) {
        int modifiers = type.getModifiers();
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static boolean isApiMember(int modifiers, boolean synthetic) {
        return (Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers)) && !synthetic;
    }

    private static String describe(Class<?> type) {
        StringBuilder text = new StringBuilder();
        text.append(kindOf(type)).append(' ').append(Modifier.toString(type.getModifiers()))
            .append(' ').append(type.getName()).append('\n');

        Type superclass = type.getGenericSuperclass();
        if (superclass != null && superclass != Object.class) {
            text.append("  extends ").append(typeName(superclass)).append('\n');
        }
        Arrays.stream(type.getGenericInterfaces())
            .map(PlatformApiBaselineTest::typeName)
            .sorted()
            .forEach(iface -> text.append("  implements ").append(iface).append('\n'));
        for (TypeVariable<?> variable : type.getTypeParameters()) {
            text.append("  type-parameter ").append(variable.getName()).append('\n');
        }
        Arrays.stream(type.getDeclaredAnnotations())
            .map(annotation -> annotation.annotationType().getName())
            .sorted()
            .forEach(name -> text.append("  annotation ").append(name).append('\n'));
        if (type.isAnnotation()) {
            text.append("  retention ").append(annotationMeta(type, java.lang.annotation.Retention.class,
                java.lang.annotation.RetentionPolicy.class)).append('\n');
            text.append("  target ").append(targets(type)).append('\n');
        }
        if (type.isEnum()) {
            text.append("  enum-constants ").append(Arrays.toString(type.getEnumConstants())).append('\n');
        }

        List<String> members = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            if (!isApiMember(field.getModifiers(), field.isSynthetic())) {
                continue;
            }
            members.add("  field " + Modifier.toString(field.getModifiers()) + ' '
                + typeName(field.getGenericType()) + ' ' + field.getName()
                + constantValue(field));
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (!isApiMember(constructor.getModifiers(), constructor.isSynthetic())) {
                continue;
            }
            members.add("  ctor " + Modifier.toString(constructor.getModifiers())
                + '(' + parameters(constructor) + ')' + declaredExceptions(constructor));
        }
        for (Method method : type.getDeclaredMethods()) {
            if (!isApiMember(method.getModifiers(), method.isSynthetic() || method.isBridge())) {
                continue;
            }
            members.add("  method " + Modifier.toString(method.getModifiers()) + ' '
                + typeName(method.getGenericReturnType()) + ' ' + method.getName()
                + '(' + parameters(method) + ')' + declaredExceptions(method)
                + defaultValue(method));
        }
        members.sort(Comparator.naturalOrder());
        members.forEach(member -> text.append(member).append('\n'));
        return text.toString();
    }

    /** Константное значение — часть контракта: его правка ломает потребителя так же, как подпись. */
    private static String constantValue(Field field) {
        if (!field.isEnumConstant() && Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers())
                && (field.getType().isPrimitive() || field.getType() == String.class)) {
            try {
                return " = " + field.get(null);
            } catch (ReflectiveOperationException | LinkageError e) {
                return " = <unavailable: " + e.getClass().getSimpleName() + ">";
            }
        }
        return field.isEnumConstant() ? " = enum-constant" : "";
    }

    private static String defaultValue(Method method) {
        Object value = method.getDefaultValue();
        return value == null ? "" : " default " + render(value);
    }

    /**
     * Канонический рендер значения по умолчанию.
     *
     * <p>Аннотация рендерится вручную: её {@code toString()} перечисляет элементы в порядке
     * объявления класса аннотации, а этот порядок JVM не гарантирует — baseline расходился бы
     * сам с собой между прогонами. Поэтому элементы берутся рефлексией и сортируются по имени.</p>
     */
    private static String render(Object value) {
        if (value instanceof Annotation annotation) {
            return renderAnnotation(annotation);
        }
        if (value instanceof Object[] array) {
            List<String> items = new ArrayList<>();
            for (Object item : array) {
                items.add(render(item));
            }
            return items.toString();
        }
        return String.valueOf(value);
    }

    private static String renderAnnotation(Annotation annotation) {
        List<String> members = new ArrayList<>();
        for (Method attribute : annotation.annotationType().getDeclaredMethods()) {
            try {
                members.add(attribute.getName() + '=' + render(attribute.invoke(annotation)));
            } catch (ReflectiveOperationException e) {
                members.add(attribute.getName() + "=<unavailable>");
            }
        }
        members.sort(Comparator.naturalOrder());
        return '@' + annotation.annotationType().getName() + '(' + String.join(", ", members) + ')';
    }

    private static String parameters(Executable executable) {
        StringBuilder text = new StringBuilder();
        Parameter[] parameters = executable.getParameters();
        for (int index = 0; index < parameters.length; index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(typeName(parameters[index].getParameterizedType()));
        }
        return text.toString();
    }

    private static String declaredExceptions(Executable executable) {
        Class<?>[] exceptions = executable.getExceptionTypes();
        if (exceptions.length == 0) {
            return "";
        }
        Set<String> names = new TreeSet<>();
        Arrays.stream(exceptions).map(Class::getName).forEach(names::add);
        return " throws " + String.join(", ", names);
    }

    private static String targets(Class<?> type) {
        java.lang.annotation.Target target = type.getAnnotation(java.lang.annotation.Target.class);
        if (target == null) {
            return "<none>";
        }
        Set<String> names = new TreeSet<>();
        Arrays.stream(target.value()).map(Enum::name).forEach(names::add);
        return names.toString();
    }

    private static <A extends Annotation, T extends Enum<T>> String annotationMeta(
            Class<?> type, Class<A> annotationType, Class<T> valueType) {
        A annotation = type.getAnnotation(annotationType);
        if (annotation == null) {
            return "<none>";
        }
        try {
            return ((Enum<?>) annotationType.getMethod("value").invoke(annotation)).name();
        } catch (ReflectiveOperationException e) {
            return "<unavailable>";
        }
    }

    private static String kindOf(Class<?> type) {
        if (type.isAnnotation()) {
            return "annotation";
        }
        if (type.isEnum()) {
            return "enum";
        }
        if (type.isRecord()) {
            return "record";
        }
        if (type.isInterface()) {
            return "interface";
        }
        return Modifier.isAbstract(type.getModifiers()) ? "abstract-class" : "class";
    }

    private static String typeName(Type type) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isArray()) {
                return typeName(clazz.getComponentType()) + "[]";
            }
            return clazz.getTypeName();
        }
        return type.getTypeName();
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className, false, PlatformApiBaselineTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("класс артефакта не найден на пути компиляции: " + className
                + " — значит артефакт не установлен, и baseline проверять нечего", e);
        }
    }

    /** Типы артефакта по его исходникам: имена берутся из дерева, классы — из артефакта. */
    private static Set<String> artifactTypeNames(Artifact artifact) {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(artifact.sourceRoot())) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = artifact.sourceRoot().relativize(file).toString()
                    .replace('\\', '/').replace(".java", "");
                names.add(relative.replace('/', '.'));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    private static String firstDifference(String reviewed, String actual) {
        String[] reviewedLines = reviewed.split("\n", -1);
        String[] actualLines = actual.split("\n", -1);
        int common = Math.min(reviewedLines.length, actualLines.length);
        for (int index = 0; index < common; index++) {
            if (!reviewedLines[index].equals(actualLines[index])) {
                return "  baseline: " + reviewedLines[index] + "\n  код:      " + actualLines[index];
            }
        }
        return reviewedLines.length == actualLines.length
            ? "  различий в общих строках нет (разошёлся состав строк)"
            : "  baseline строк: " + reviewedLines.length + ", код: " + actualLines.length;
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
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
