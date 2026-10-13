package org.ipro.register;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-R S0 — у каждого production-типа артефакта {@code platform-register} есть ровно одна
 * семантическая роль, и решение записано в reviewed-реестре
 * {@code META-INF/register-surface.txt}.
 *
 * <p>Почему забор стоит в модуле, а не в приложении. Предмет проверки — граница обещания
 * совместимости артефакта, а не доступность его типов приложению. Модуль — тот, кто расширяет
 * эту границу каждым срезом (S1 добавит читатель, S2 — правила ключа, S3 — протокол набора
 * владельца), поэтому решение о роли обязано падать на сборке самого артефакта. Побочный
 * эффект нужен тоже: у модуля появляется собственный прогон тестов, до S3, а не «потом»
 * (план выноса, приёмка §6.5).</p>
 *
 * <p>Проверяются три свойства, и все они — про <b>расхождение</b>, а не про наличие списка:</p>
 * <ol>
 * <li><b>Полнота.</b> Множество ролей совпадает с множеством production-типов модуля: новый тип
 *     без роли ломает сборку, лишняя запись — тоже (она означала бы решение о коде, которого
 *     нет). Словарь ролей замкнут: черновиковая роль — незакрытая развилка, а не классификация.</li>
 * <li><b>Самосогласованность файла.</b> Заголовок раздела {@code # ---- ROLE (n)} и число записей
 *     под ним обязаны совпадать, а запись под чужим заголовком — ошибка: иначе бюджет ролей
 *     в шапке реестра живёт своей жизнью и перестаёт быть проверяемым числом.</li>
 * <li><b>Замыкание API.</b> Публичная сигнатура типа с ролью {@code APP_API}/{@code APP_SPI} не
 *     может называть тип с ролью {@code INTERNAL}: обещание совместимости распространилось бы на
 *     реализацию, которую платформа вправе менять свободно. Сигнатурой считается наследование,
 *     поля и методы; аргументы аннотаций — нет, и единственное такое место
 *     ({@code @EntityListeners(RecordedRegisterEntryWriteGuard.class)}) названо в шапке реестра
 *     явно, потому что видно в baseline артефакта.</li>
 * </ol>
 */
class RegisterSurfaceRoleTest {

    /** Рабочий каталог модуля: тест запускается его же сборкой, а не из корня чекаута. */
    private static final Path MODULE = Path.of("").toAbsolutePath();

    private static final Path REGISTRY = MODULE.resolve("src/main/resources/META-INF/register-surface.txt");

    private static final Path SOURCES = MODULE.resolve("src/main/java");

    private static final String MODULE_PACKAGE = "org.ipro.register.";

    private static final Set<String> ROLES = Set.of("APP_API", "APP_SPI", "MODULE_API", "INTERNAL");

    private static final Set<String> APPLICATION_FACING = Set.of("APP_API", "APP_SPI");

    private static final Pattern SECTION = Pattern.compile("^#\\s*-+\\s*(.+?)\\s*\\((\\d+)\\)\\s*$");

    private static final Pattern ENTRY = Pattern.compile("^(\\w+) (\\S+)$");

    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    @Test
    void everyModuleTypeHasExactlyOneReviewedRole() {
        Set<String> types = productionTypes();
        Map<String, String> roles = readRegistry().roles();

        Set<String> unclassified = new TreeSet<>(types);
        unclassified.removeAll(roles.keySet());
        Set<String> stale = new TreeSet<>(roles.keySet());
        stale.removeAll(types);

        assertThat(unclassified)
            .as("новый production-тип модуля обязан получить роль в реестре: иначе он молча"
                + " замерзает как часть поверхности, и решение о нём не принималось")
            .isEmpty();
        assertThat(stale)
            .as("запись реестра описывает тип, которого в модуле нет: решение о роли должно"
                + " относиться к существующему коду, а не к истории")
            .isEmpty();
        assertThat(roles.values())
            .as("словарь ролей замкнут: APP_API | APP_SPI | MODULE_API | INTERNAL")
            .allMatch(ROLES::contains);
        assertThat(types)
            .as("проверка не должна быть вакуумной: срез S0 переносит 17 типов, и реестр их"
                + " называет поимённо")
            .hasSize(17);
    }

    @Test
    void registrySectionsAgreeWithTheirPerRoleBudget() {
        Map<String, Integer> declared = new LinkedHashMap<>();
        Map<String, Integer> counted = new LinkedHashMap<>();
        Map<String, String> clashes = new LinkedHashMap<>();
        String section = null;

        for (String line : lines(REGISTRY)) {
            Matcher header = SECTION.matcher(line);
            if (header.matches()) {
                section = header.group(1).trim();
                declared.put(section, Integer.parseInt(header.group(2)));
                continue;
            }
            Matcher entry = ENTRY.matcher(line);
            if (!entry.matches()) {
                continue;
            }
            String role = entry.group(1);
            counted.merge(role, 1, Integer::sum);
            if (section != null && !section.equals(role)) {
                clashes.put(entry.group(2), role + " под заголовком " + section);
            }
        }

        for (String role : ROLES) {
            // Роль с нулём записей — измеренный факт, а не пропуск раздела: бюджет обязан
            // совпадать с числом записей, включая нулевой. MODULE_API пуст на S0, потому что
            // следующий срез ещё не назвал ни одного типа регистра из другого модуля.
            counted.putIfAbsent(role, 0);
        }

        assertThat(declared.keySet())
            .as("разделы реестра обязаны называть все четыре роли: иначе пустая роль неотличима"
                + " от роли, о которой не подумали")
            .containsExactlyInAnyOrderElementsOf(ROLES);
        assertThat(counted.keySet())
            .as("запись с ролью вне словаря — незакрытая развилка, а не решение")
            .containsExactlyInAnyOrderElementsOf(ROLES);
        assertThat(clashes)
            .as("запись под чужим заголовком: бюджет раздела перестаёт быть проверяемым числом")
            .isEmpty();
        assertThat(counted)
            .as("число записей под заголовком обязано совпадать с бюджетом в нём")
            .isEqualTo(declared);
    }

    @Test
    void internalTypesAreNotNamedInApplicationFacingSignatures() {
        Map<String, String> roles = readRegistry().roles();
        List<String> problems = new ArrayList<>();

        for (Map.Entry<String, String> entry : roles.entrySet()) {
            if (!APPLICATION_FACING.contains(entry.getValue())) {
                continue;
            }
            Class<?> type = load(entry.getKey());
            Set<String> signatureTypes = new LinkedHashSet<>();
            collectSignatureTypes(type, signatureTypes);
            for (String named : signatureTypes) {
                if (!named.startsWith(MODULE_PACKAGE)) {
                    continue;
                }
                String owner = ownerOf(named);
                String role = roles.get(owner);
                if (role == null) {
                    problems.add(entry.getKey() + " (" + entry.getValue() + ") называет " + named
                        + ", у которого нет роли в реестре");
                } else if ("INTERNAL".equals(role)) {
                    problems.add(entry.getKey() + " (" + entry.getValue() + ") называет " + named
                        + " в публичной сигнатуре, а его роль — INTERNAL: обещание совместимости"
                        + " распространилось бы на реализацию");
                }
            }
        }

        assertThat(problems)
            .as("замыкание API: обещание совместимости не должно выводить за собой реализацию."
                + " Если тип обязан быть видимым через API, ему назначается APP_API/APP_SPI,"
                + " а не исключение из правила")
            .isEmpty();
    }

    /** Production-типы модуля: по одному на исходный файл, как их видит поверхность артефакта. */
    private static Set<String> productionTypes() {
        Set<String> types = new TreeSet<>();
        for (Path source : javaSources(SOURCES)) {
            Matcher matcher = PACKAGE.matcher(read(source));
            if (!matcher.find()) {
                throw new IllegalStateException("нет package: " + source);
            }
            String name = source.getFileName().toString();
            types.add(matcher.group(1) + "." + name.substring(0, name.length() - ".java".length()));
        }
        return types;
    }

    /** Owner для binary-имени вложенного типа: роль наследуется владельцем. */
    private static String ownerOf(String className) {
        int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }

    private static Class<?> load(String className) {
        try {
            return Class.forName(className, false, RegisterSurfaceRoleTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("production-тип модуля не найден на classpath: "
                + className + " — значит забор проверяет не тот артефакт", e);
        }
    }

    /** Типы, названные публичной и protected-частью сигнатуры типа. */
    private static void collectSignatureTypes(Class<?> type, Set<String> out) {
        collect(type.getGenericSuperclass(), out);
        for (Type iface : type.getGenericInterfaces()) {
            collect(iface, out);
        }
        for (TypeVariable<?> variable : type.getTypeParameters()) {
            for (Type bound : variable.getBounds()) {
                collect(bound, out);
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (isApiMember(field.getModifiers())) {
                collect(field.getGenericType(), out);
            }
        }
        for (Executable executable : declaredExecutables(type)) {
            if (!isApiMember(executable.getModifiers())) {
                continue;
            }
            for (Type parameter : executable.getGenericParameterTypes()) {
                collect(parameter, out);
            }
            for (Type exception : executable.getGenericExceptionTypes()) {
                collect(exception, out);
            }
            if (executable instanceof Method method) {
                collect(method.getGenericReturnType(), out);
            }
        }
    }

    private static List<Executable> declaredExecutables(Class<?> type) {
        List<Executable> executables = new ArrayList<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            executables.add(constructor);
        }
        for (Method method : type.getDeclaredMethods()) {
            executables.add(method);
        }
        return executables;
    }

    private static boolean isApiMember(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
    }

    private static void collect(Type type, Set<String> out) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isArray()) {
                collect(clazz.getComponentType(), out);
            } else if (!clazz.isPrimitive() && !clazz.isSynthetic()
                    && clazz.getName().startsWith(MODULE_PACKAGE)) {
                out.add(clazz.getName());
            }
        } else if (type instanceof ParameterizedType parameterized) {
            collect(parameterized.getRawType(), out);
            for (Type argument : parameterized.getActualTypeArguments()) {
                collect(argument, out);
            }
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                collect(bound, out);
            }
            for (Type bound : wildcard.getLowerBounds()) {
                collect(bound, out);
            }
        } else if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) {
                collect(bound, out);
            }
        } else if (type instanceof GenericArrayType array) {
            collect(array.getGenericComponentType(), out);
        }
    }

    private record Registry(Map<String, String> roles) {
    }

    private static Registry readRegistry() {
        Map<String, String> roles = new LinkedHashMap<>();
        for (String line : lines(REGISTRY)) {
            Matcher entry = ENTRY.matcher(line);
            if (!entry.matches()) {
                continue;
            }
            String role = entry.group(1);
            if (!ROLES.contains(role)) {
                throw new IllegalStateException("неизвестная роль в реестре: " + line);
            }
            String previous = roles.put(entry.group(2), role);
            if (previous != null) {
                throw new IllegalStateException("тип записан дважды: " + entry.group(2)
                    + " (" + previous + " и " + role + ")");
            }
        }
        return new Registry(roles);
    }

    private static List<Path> javaSources(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("нет каталога исходников модуля: " + root);
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> lines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
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
