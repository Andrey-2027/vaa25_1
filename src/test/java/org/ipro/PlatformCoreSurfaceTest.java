package org.ipro;

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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3.3 — семантическая поверхность {@code platform-core}: у каждого production-типа есть
 * ровно одна reviewed-роль.
 *
 * <p>Зачем отдельный реестр. D1-реестр ({@code platform-public-surface.txt}) описывает только
 * <b>потребление приложением</b>: он отвечает на вопрос «что приложение вправе называть». Тип,
 * которого приложение не называет, не получал никакой роли, и из этого следовали две дыры:</p>
 * <ul>
 * <li>обещание «semantic internal можно менять свободно» нельзя было проверить — не существовало
 *     списка типов, к которым оно относится;</li>
 * <li>44 типа, образующие контракт между модулями (metadata/data/fetch/search внутри платформы),
 *     не были зафиксированы вовсе, хотя их изменение ломает соседний модуль так же, как ломает
 *     приложение.</li>
 * </ul>
 *
 * <p>Здесь роль есть у каждого из 94 production-типов {@code platform-core}: {@code APP_API},
 * {@code APP_SPI}, {@code MODULE_API} или {@code INTERNAL}. Роль — reviewed-решение, записанное
 * рядом с FQN, а не вывод теста: иначе проверялось бы то же правило, которым список построен.</p>
 *
 * <p>Доступ приложения к типам, у которых роль не {@code APP_*}, не смешивается с ролью: он
 * зафиксирован отдельной ортогональной записью {@code test-usage} (ссылки тестов приложения) и
 * legacy-записями D1-реестра (ссылки production-кода). Это и есть требование «legacy-доступ —
 * отдельная запись, а не пятая роль».</p>
 */
class PlatformCoreSurfaceTest {

    private static final Path CORE_SOURCES = Path.of("platform-core/src/main/java");

    private static final Path APPLICATION_MAIN = Path.of("src/main/java/org/ip");

    private static final Path APPLICATION_TEST = Path.of("src/test/java/org/ip");

    /** Корень тестовых исходников: в нём и записаны пути overlay-записей. */
    private static final Path TEST_SOURCE_ROOT = Path.of("src/test/java");

    private static final Path CORE_REGISTRY = Path.of("src/test/resources/platform-core-surface.txt");

    private static final Path APPLICATION_REGISTRY = Path.of("src/test/resources/platform-public-surface.txt");

    private enum Role {
        APP_API, APP_SPI, MODULE_API, INTERNAL
    }

    private record CoreRegistry(Map<String, Role> roles, Map<String, Set<String>> testUsage) {
    }

    /** D1-роль: что приложение делает с типом (называет / реализует / пробивается внутрь). */
    private enum ApplicationRole {
        API, SPI, LEGACY_INTERNAL
    }

    @Test
    void everyCoreProductionTypeHasExactlyOneReviewedRole() {
        CoreRegistry registry = readCoreRegistry();
        Set<String> productionTypes = coreProductionTypes();

        Set<String> withoutRole = new TreeSet<>(productionTypes);
        withoutRole.removeAll(registry.roles().keySet());
        Set<String> stale = new TreeSet<>(registry.roles().keySet());
        stale.removeAll(productionTypes);

        assertThat(withoutRole)
            .as("production-тип platform-core без роли: новый класс обязан получить роль сразу,"
                + " иначе правило «internal можно менять свободно» снова становится непроверяемым")
            .isEmpty();
        assertThat(stale)
            .as("реестр ролей описывает тип, которого в platform-core больше нет: список обязан"
                + " совпадать с деревом в обе стороны")
            .isEmpty();
        assertThat(registry.roles().values())
            .as("каждая роль должна быть представлена, иначе реестр закрывает не то, что думали")
            .containsAll(List.of(Role.values()));
        assertThat(productionTypes)
            .as("проверка не должна быть вакуумной")
            .hasSize(94);
    }

    /**
     * Согласованность двух реестров: D1 отвечает на вопрос «что делает приложение», этот — «какой
     * контракт у типа». Расхождение означает, что одно из двух решений устарело, и непонятно,
     * какое.
     */
    @Test
    void coreRolesAgreeWithTheApplicationUsageRegistry() {
        CoreRegistry registry = readCoreRegistry();
        Map<String, ApplicationRole> application = readApplicationRegistry();
        Set<String> namedByApplication = applicationNamedTypes();
        Set<String> closureMembers = apiClosureMembers(registry, application);

        Map<String, String> problems = new TreeMap<>();
        for (Map.Entry<String, Role> entry : registry.roles().entrySet()) {
            String fqn = entry.getKey();
            Role role = entry.getValue();
            ApplicationRole applicationRole = application.entrySet().stream()
                .filter(candidate -> covers(candidate.getKey(), fqn))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
            boolean named = namedByApplication.stream().anyMatch(name -> covers(fqn, name));

            boolean closureMember = closureMembers.contains(fqn);
            if (applicationRole == ApplicationRole.API && role != Role.APP_API) {
                problems.put(fqn, "приложение считает тип API, а роль в core — " + role);
            } else if (applicationRole == ApplicationRole.SPI && role != Role.APP_SPI) {
                problems.put(fqn, "приложение реализует тип (SPI), а роль в core — " + role);
            } else if (applicationRole == null && role == Role.APP_API && !closureMember) {
                problems.put(fqn, "роль APP_API, но приложение тип не называет: APP_API — это"
                    + " поддерживаемый API приложения либо правило 2 (тип в публичной сигнатуре"
                    + " APP_API-типа), а ни то, ни другое здесь не доказано");
            } else if (applicationRole == null && role == Role.APP_SPI && !closureMember) {
                problems.put(fqn, "роль APP_SPI, но приложение тип не реализует и не называет");
            } else if (applicationRole == ApplicationRole.LEGACY_INTERNAL
                    && role != Role.MODULE_API && role != Role.INTERNAL) {
                problems.put(fqn, "D1 фиксирует reach-through приложения, а роль " + role
                    + ": legacy-доступ не превращает тип в API");
            } else if (applicationRole == null && named && role != Role.MODULE_API
                    && role != Role.INTERNAL) {
                problems.put(fqn, "тип назван приложением, но роль " + role);
            }
            if (applicationRole != null && applicationRole != ApplicationRole.LEGACY_INTERNAL
                    && role == Role.MODULE_API) {
                problems.put(fqn, "приложение называет тип как " + applicationRole
                    + ", а роль MODULE_API: контракт модулей не может быть уже достигнут приложением");
            }
        }

        assertThat(problems)
            .as("две reviewed-классификации одного типа обязаны сходиться: D1 (что делает"
                + " приложение) и D3.3 (какой контракт у типа). Расхождение — устаревшее решение")
            .isEmpty();
    }

    /**
     * Члены API closure: типы в публичных сигнатурах тех {@code APP_API}/{@code APP_SPI}-типов,
     * которые приложение действительно называет или реализует. Именно их правило 2 переводит на
     * тот же уровень контракта — без этого решения роль такого типа пришлось бы считать ошибкой
     * реестра, хотя она следствие правила.
     */
    private static Set<String> apiClosureMembers(
            CoreRegistry registry, Map<String, ApplicationRole> application) {
        ClassLoader loader = PlatformCoreSurfaceTest.class.getClassLoader();
        List<String> queue = new ArrayList<>();
        for (Map.Entry<String, Role> entry : registry.roles().entrySet()) {
            if (entry.getValue() != Role.APP_API && entry.getValue() != Role.APP_SPI) {
                continue;
            }
            ApplicationRole applicationRole = application.entrySet().stream()
                .filter(candidate -> covers(candidate.getKey(), entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
            if (applicationRole == ApplicationRole.API || applicationRole == ApplicationRole.SPI) {
                queue.add(entry.getKey());
            }
        }

        // Замыкание транзитивно: APP_API-тип может стоять в сигнатуре другого APP_API-типа,
        // который приложение тоже не называет напрямую. Один шаг здесь был бы дырой ровно того
        // же рода, что и изначальное «только по импортам».
        Set<String> members = new TreeSet<>();
        while (!queue.isEmpty()) {
            String type = queue.remove(0);
            Set<String> signatureTypes = new TreeSet<>();
            collectSignatureTypes(load(type, loader), signatureTypes);
            for (String referenced : signatureTypes) {
                String owner = ownerOf(referenced);
                if (!registry.roles().containsKey(owner)) {
                    continue;
                }
                if (members.add(owner)) {
                    queue.add(owner);
                }
            }
        }
        return members;
    }

    /**
     * Приложение не должно называть тип, у которого роль {@code INTERNAL}, — кроме тех ссылок,
     * что уже заморожены legacy-записями D1-реестра (это измеренный долг, а не разрешение).
     */
    @Test
    void applicationMainCodeDoesNotReachIntoInternalCoreTypes() {
        CoreRegistry registry = readCoreRegistry();
        Set<String> legacyFrozen = readApplicationRegistry().entrySet().stream()
            .filter(entry -> entry.getValue() == ApplicationRole.LEGACY_INTERNAL)
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        Map<String, Set<String>> offenders = new TreeMap<>();
        for (Path source : javaSources(APPLICATION_MAIN)) {
            String code = withoutCommentsAndLiterals(read(source));
            for (Map.Entry<String, Role> entry : registry.roles().entrySet()) {
                if (entry.getValue() != Role.INTERNAL || covered(legacyFrozen, entry.getKey())) {
                    continue;
                }
                if (mentions(code, entry.getKey())) {
                    offenders.computeIfAbsent(entry.getKey(), ignored -> new TreeSet<>())
                        .add(APPLICATION_MAIN.getParent().getParent().relativize(source)
                            .toString().replace('\\', '/'));
                }
            }
        }

        assertThat(offenders)
            .as("приложение называет тип, объявленный INTERNAL. Внутренний тип приложение знать"
                + " не должно: если он нужен, его место в APP_API/APP_SPI (это решение), а если"
                + " ссылка временная — она обязана быть заморожена legacy-записью D1-реестра")
            .isEmpty();
    }

    /**
     * API closure: тип, объявленный {@code INTERNAL}, не может появляться в публичной сигнатуре
     * {@code APP_API}/{@code APP_SPI} — иначе правило «internal можно менять свободно» нарушается
     * молча: изменение внутреннего класса ломает приложение, которому этот тип виден через API.
     */
    @Test
    void internalTypesAreNotExposedInApplicationFacingSignatures() {
        CoreRegistry registry = readCoreRegistry();
        ClassLoader loader = PlatformCoreSurfaceTest.class.getClassLoader();

        Map<String, Set<String>> violations = new TreeMap<>();
        for (Map.Entry<String, Role> entry : registry.roles().entrySet()) {
            if (entry.getValue() != Role.APP_API && entry.getValue() != Role.APP_SPI) {
                continue;
            }
            Class<?> type = load(entry.getKey(), loader);
            Set<String> signatureTypes = new TreeSet<>();
            collectSignatureTypes(type, signatureTypes);
            for (String referenced : signatureTypes) {
                String owner = ownerOf(referenced);
                Role role = registry.roles().get(owner);
                if (role == Role.INTERNAL) {
                    violations.computeIfAbsent(entry.getKey(), ignored -> new TreeSet<>()).add(referenced);
                }
            }
        }

        assertThat(violations)
            .as("публичная сигнатура APP_API/APP_SPI называет INTERNAL-тип: обещание"
                + " совместимости распространилось на реализацию. Либо тип обязан стать APP_API"
                + " (если приложение вправе его знать), либо ссылку надо закрыть")
            .isEmpty();
    }

    /**
     * Overlay ссылок тестов приложения: ортогонален роли и только сокращается.
     *
     * <p>Тест приложения вправе пробиваться внутрь глубже, чем прикладной код, но это остаётся
     * долгом: запись фиксирует <b>точный</b> набор файлов, поэтому новая ссылка ломает сборку, а
     * снятая обязана исчезнуть из реестра.</p>
     */
    @Test
    void applicationTestReferencesToInternalOrModuleTypesAreFrozenPerFile() {
        CoreRegistry registry = readCoreRegistry();
        Map<String, Set<String>> actual = actualTestUsage(registry);

        for (Map.Entry<String, Set<String>> entry : registry.testUsage().entrySet()) {
            Role role = registry.roles().get(entry.getKey());
            assertThat(role)
                .as("test-usage без базовой роли: overlay ортогонален роли, но не заменяет её")
                .isNotNull();
            assertThat(role)
                .as("тип с ролью %s не может держать доступ из тестов приложения: тест проверяет"
                    + " поддерживаемый API, а не реализацию", role)
                .isNotEqualTo(Role.APP_API);
            assertThat(role)
                .as("тип с ролью %s не может держать доступ из тестов приложения: тест проверяет"
                    + " поддерживаемый API, а не реализацию", role)
                .isNotEqualTo(Role.APP_SPI);
            assertThat(entry.getValue())
                .as("у test-usage обязан быть зафиксирован набор файлов, иначе заморозка «новая"
                    + " ссылка запрещена» не работает")
                .isNotEmpty();

            Set<String> added = new TreeSet<>(actual.getOrDefault(entry.getKey(), Set.of()));
            added.removeAll(entry.getValue());
            Set<String> removed = new TreeSet<>(entry.getValue());
            removed.removeAll(actual.getOrDefault(entry.getKey(), Set.of()));

            assertThat(added)
                .as("новый тест начал называть %s. Если это осознанное решение — оно должно быть"
                    + " записано в реестре, а не появиться само", entry.getKey())
                .isEmpty();
            assertThat(removed)
                .as("тест больше не называет %s: снятую ссылку надо убрать из реестра, overlay"
                    + " только сокращается", entry.getKey())
                .isEmpty();
        }

        Set<String> untracked = new TreeSet<>(actual.keySet());
        untracked.removeAll(registry.testUsage().keySet());
        assertThat(untracked)
            .as("тест ссылается на тип, которого нет в overlay: ссылка должна быть названа явно")
            .isEmpty();
    }

    /** Production-типы core: top-level исходники модуля. */
    private static Set<String> coreProductionTypes() {
        Set<String> types = new TreeSet<>();
        for (Path source : javaSources(CORE_SOURCES)) {
            String name = CORE_SOURCES.relativize(source).toString().replace('\\', '/')
                .replace(".java", "").replace('/', '.');
            assertThat(types.add(name))
                .as("дубликат FQN %s: один и тот же тип не может быть объявлен дважды", name)
                .isTrue();
        }
        return types;
    }

    /** Типы core, которые называет приложение: критерий по имени (как у заморозок реестров). */
    private static Set<String> applicationNamedTypes() {
        Set<String> named = new TreeSet<>();
        for (Path source : javaSources(APPLICATION_MAIN)) {
            named.addAll(simpleNameTokens(withoutCommentsAndLiterals(read(source))));
        }
        return named;
    }

    /** Типы core, которые называют тесты приложения: критерий по имени, как у D1-заморозки. */
    private static Map<String, Set<String>> actualTestUsage(CoreRegistry registry) {
        List<Path> sources = javaSources(APPLICATION_TEST);
        Map<String, Set<String>> usages = new TreeMap<>();
        for (String fqn : registry.testUsage().keySet()) {
            Set<String> files = new TreeSet<>();
            for (Path source : sources) {
                if (mentions(read(source), fqn)) {
                    files.add(TEST_SOURCE_ROOT.relativize(source).toString().replace('\\', '/'));
                }
            }
            usages.put(fqn, files);
        }
        return usages;
    }

    /** Встречается ли тип в коде как отдельное слово (в комментарии — не считается). */
    private static boolean mentions(String code, String fqn) {
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        return Pattern.compile("\\b" + Pattern.quote(simple) + "\\b")
            .matcher(withoutCommentsAndLiterals(code)).find();
    }

    private static boolean covers(String owner, String fqn) {
        return fqn.equals(owner) || fqn.startsWith(owner + ".");
    }

    private static boolean covered(Set<String> owners, String fqn) {
        return owners.stream().anyMatch(owner -> covers(owner, fqn));
    }

    /** Owner для binary-имени вложенного типа: роль наследуется (§5 правил классификации). */
    private static String ownerOf(String className) {
        int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }

    private static Class<?> load(String className, ClassLoader loader) {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("production-тип platform-core не найден на classpath: "
                + className + " — значит модуль не установлен, и проверять его поверхность нечего", e);
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
            } else if (!clazz.isPrimitive() && !clazz.isSynthetic() && clazz.getName().startsWith("org.ipro.")) {
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

    private static Set<String> simpleNameTokens(String code) {
        Set<String> tokens = new TreeSet<>();
        Matcher matcher = Pattern.compile("\\b[A-Z][A-Za-z0-9_]*\\b").matcher(code);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /**
     * Убирает комментарии и строковые литералы: упоминание в комментарии — не ссылка, а именно на
     * этом ошибались прежние редакции гейтов (они падали на объяснении, зачем правило написано).
     */
    private static String withoutCommentsAndLiterals(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            char current = text.charAt(index);
            char next = index + 1 < text.length() ? text.charAt(index + 1) : '\0';
            if (current == '/' && next == '/') {
                while (index < text.length() && text.charAt(index) != '\n') {
                    index++;
                }
            } else if (current == '/' && next == '*') {
                index += 2;
                while (index < text.length()
                        && !(text.charAt(index) == '*' && index + 1 < text.length()
                            && text.charAt(index + 1) == '/')) {
                    index++;
                }
                index += 2;
            } else if (current == '"' || current == '\'') {
                char quote = current;
                index++;
                while (index < text.length() && text.charAt(index) != quote) {
                    index += text.charAt(index) == '\\' ? 2 : 1;
                }
                index++;
            } else {
                out.append(current);
                index++;
            }
        }
        return out.toString();
    }

    private static CoreRegistry readCoreRegistry() {
        Map<String, Role> roles = new LinkedHashMap<>();
        Map<String, Set<String>> testUsage = new LinkedHashMap<>();
        for (String line : lines(CORE_REGISTRY)) {
            if (line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts[0].equalsIgnoreCase("test-usage")) {
                if (parts.length < 3) {
                    throw new IllegalStateException("test-usage без файлов: " + line);
                }
                Set<String> files = new LinkedHashSet<>(List.of(parts).subList(2, parts.length));
                if (testUsage.put(parts[1], files) != null) {
                    throw new IllegalStateException("два test-usage для одного типа: " + line);
                }
                continue;
            }
            if (parts.length != 2) {
                throw new IllegalStateException("Строка реестра не разобрана: " + line);
            }
            Role role;
            try {
                role = Role.valueOf(parts[0].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Неизвестная роль в реестре: " + line, e);
            }
            if (roles.put(parts[1], role) != null) {
                throw new IllegalStateException("две роли для одного типа: " + line);
            }
        }
        return new CoreRegistry(roles, testUsage);
    }

    private static Map<String, ApplicationRole> readApplicationRegistry() {
        Map<String, ApplicationRole> roles = new LinkedHashMap<>();
        for (String line : lines(APPLICATION_REGISTRY)) {
            if (line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            roles.put(parts[1], ApplicationRole.valueOf(parts[0].toUpperCase(Locale.ROOT)
                .replace('-', '_')));
        }
        return roles;
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
