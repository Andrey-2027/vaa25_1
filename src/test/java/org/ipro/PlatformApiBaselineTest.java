package org.ipro;

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

    /**
     * Reviewed перечень артефактов, чья поверхность зафиксирована. Он обязан совпадать с
     * набором публикуемых модулей: артефакт, попавший в манифест и не попавший сюда, можно
     * опубликовать, не заметив ни одного несовместимого изменения.
     */
    private static final List<Artifact> ARTIFACTS = List.of(
        new Artifact("platform-contracts", Path.of("platform-contracts/src/main/java")),
        new Artifact("platform-events", Path.of("platform-events/src/main/java")),
        new Artifact("platform-persistence", Path.of("platform-persistence/src/main/java")),
        new Artifact("platform-core", Path.of("platform-core/src/main/java")),
        new Artifact("platform-identity-api",
            Path.of("../crudui/platform-identity-api/src/main/java")));

    private static final Path BASELINE_DIRECTORY = Path.of("src/test/resources/platform-api-baseline");

    @Test
    void artifactPublicSurfaceMatchesItsReviewedBaseline() {
        boolean write = Boolean.getBoolean(WRITE_PROPERTY);
        List<String> problems = new ArrayList<>();
        int verified = 0;

        for (Artifact artifact : ARTIFACTS) {
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
            verified++;
        }

        assertThat(problems)
            .as("изменение публичной подписи — это изменение контракта. Reviewed-списки типов"
                + " такое не ловят: они видят только состав. Если изменение намеренное,"
                + " перезапишите baseline (%s=true) и объясните его в ревью", WRITE_PROPERTY)
            .isEmpty();
        assertThat(verified)
            .as("проверка не должна быть вакуумной")
            .isGreaterThanOrEqualTo(3);
    }

    /**
     * Поверхность артефакта: его публичные и protected типы (включая вложенные), описанные
     * канонически и в стабильном порядке. Приватные члены в baseline не попадают намеренно:
     * это не контракт, и их переименование не должно требовать правки ревьюируемого файла —
     * иначе baseline быстро перестанут читать.
     */
    private static String describe(Artifact artifact) {
        List<String> descriptions = new ArrayList<>();
        for (String className : artifactTypeNames(artifact)) {
            collectApiTypes(loadClass(className), descriptions);
        }
        descriptions.sort(Comparator.naturalOrder());
        StringBuilder text = new StringBuilder();
        text.append("# ").append(artifact.artifactId()).append(": публичная поверхность")
            .append(" (перезапись: -D").append(WRITE_PROPERTY).append("=true)\n");
        descriptions.forEach(description -> text.append(description).append('\n'));
        return text.toString();
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

    /** Артефакт и его исходники. */
    private record Artifact(String artifactId, Path sourceRoot) {
    }
}
