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
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3.5.0 — измерение до переноса: у каждого production-типа будущего модуля
 * {@code platform-vaadin} есть ровно одна семантическая роль.
 *
 * <p>Почему это отдельный забор, а не часть теста модуля. План D3.5 прямо называет риск:
 * перенести 80 + 7 + 3 типа и зафиксировать получившуюся поверхность целиком
 * ({@code TEMPORARY_ALL_PUBLIC}) — значит повторить долг D3.3 в большем масштабе. Роли были
 * назначены <b>до</b> физического переноса, пока решение о каждом типе ещё можно принять, а не
 * подтвердить задним числом. После переноса (D3.5.4) забор смотрит на исходники модуля, а не на
 * каталоги-кандидаты в дереве приложения: предмет проверки — решение о роли типа, и оно не
 * должно зависеть от того, в каком артефакте тип лежит сегодня.</p>
 *
 * <p>Проверяются семь свойств, и все они — про <b>расхождение</b>, а не про наличие списка:</p>
 * <ol>
 * <li><b>Полнота.</b> Множество ролей совпадает с множеством production-типов трёх корней: новый
 *     тип без роли ломает сборку, лишняя запись — тоже (она означала бы, что решение описывает
 *     код, которого нет).</li>
 * <li><b>Самосогласованность файла.</b> Заголовок раздела {@code # ---- ROLE (n)} и число записей
 *     под ним обязаны совпадать, а запись под чужим заголовком — ошибка. Иначе бюджет в шапке
 *     реестра живёт своей жизнью и перестаёт быть проверяемым числом.</li>
 * <li><b>Замкнутый словарь ролей.</b> Черновиковые роли (например {@code APP_API_DRAFT}) не
 *     являются решениями: их присутствие — незакрытая развилка, а не классификация.</li>
 * <li><b>API closure</b> (D3.5.8). Публичная сигнатура типа с ролью {@code APP_API}/{@code APP_SPI}
 *     не может называть тип с ролью {@code INTERNAL}: обещание совместимости распространилось бы
 *     на реализацию, которую платформа вправе менять свободно. Для UI-модуля это свойство не
 *     измерялось ни разу до D3.5.8, и на первом замере нарушителей оказалось 14 из 42 — поэтому
 *     роли, а не сигнатуры, были приведены в соответствие (см. заголовок реестра).</li>
 * <li><b>Приложение не дотягивается внутрь.</b> Production-код приложения не называет
 *     {@code INTERNAL}-тип модуля вовсе, а {@code MODULE_API}-тип — только если ссылка
 *     зарегистрирована записью {@code legacy-internal} в D1-реестре (измеренный долг, а не
 *     разрешение).</li>
 * <li><b>Согласованность двух реестров.</b> Роль типа в D1-реестре приложения и роль того же типа
 *     в реестре модуля обязаны сходиться: {@code API} ↔ {@code APP_API}, {@code SPI} ↔
 *     {@code APP_SPI}, {@code legacy-internal} ↔ {@code MODULE_API}/{@code INTERNAL}. Расхождение
 *     такого рода находилось вручную в D3.5.7 — теперь оно ломает сборку.</li>
 * <li><b>Заморозка ссылок тестов.</b> Тест приложения вправе пробиваться внутрь глубже, чем
 *     прикладной код, но это остаётся долгом: overlay {@code test-usage} фиксирует
 *     <b>точный</b> набор файлов на тип, поэтому новая ссылка ломает сборку, а снятая обязана
 *     исчезнуть из реестра. Полнота overlay считается от реестра ролей, а не от ключей самого
 *     overlay.</li>
 * </ol>
 */
class PlatformVaadinSurfaceTest {

    private static final Path REGISTRY = Path.of("src/test/resources/platform-vaadin-surface.txt");

    /** D1-реестр приложения: роли типов платформы по тому, что приложение с ними делает. */
    private static final Path APPLICATION_REGISTRY = Path.of("src/test/resources/platform-public-surface.txt");

    /**
     * Корни модуля после D3.5.4: form и Vaadin-слой (Entity Explorer, поиск, адаптер
     * телеметрии — последний переехал в {@code org.ipro.vaadin.telemetry} со сменой пакета).
     */
    private static final List<Path> MODULE_ROOTS = List.of(
        Path.of("platform-vaadin/src/main/java/org/ipro/form"),
        Path.of("platform-vaadin/src/main/java/org/ipro/vaadin"));

    private static final Path APPLICATION_MAIN_SOURCES = Path.of("src/main/java");

    private static final Path APPLICATION_TEST_SOURCES = Path.of("src/test/java");

    /** Корни, по которым D1-реестр обязан совпадать с составом модуля. */
    private static final List<String> MODULE_PACKAGE_PREFIXES = List.of("org.ipro.form.", "org.ipro.vaadin.");

    private static final Set<String> ROLES = Set.of("APP_API", "APP_SPI", "MODULE_API", "INTERNAL");

    /** Раздел overlay в реестре: роли не назначает, но бюджет у него такой же проверяемый. */
    private static final String OVERLAY_SECTION = "test-usage overlay";

    private static final Pattern SECTION = Pattern.compile("^#\\s*-+\\s*(.+?)\\s*\\((\\d+)\\)\\s*$");
    private static final Pattern ENTRY = Pattern.compile("^(\\w+) (\\S+)$");
    private static final Pattern OVERLAY_ROW = Pattern.compile("^test-usage (\\S+)(?:\\s+(.*))?$");
    private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    @Test
    void everyFutureModuleTypeHasExactlyOneReviewedRole() {
        Set<String> actualTypes = productionTypes();
        Set<String> reviewedTypes = new TreeSet<>(readRegistry().roles().keySet());

        Set<String> withoutRole = new TreeSet<>(actualTypes);
        withoutRole.removeAll(reviewedTypes);
        Set<String> stale = new TreeSet<>(reviewedTypes);
        stale.removeAll(actualTypes);

        assertThat(actualTypes)
            .as("забор не должен быть вакуумным: количество production-типов модуля —"
                + " это замер D3.5.0 плюс FormNavigator из D3.5.1, LookupComboHelper из D3.5.2"
                + " и FilterLookupOptions из D3.5.2b, а не догадка. Число меняется только вместе"
                + " с переносом типов и обновлением реестра; D3.5.4 перенёс те же 93 типа,"
                + " поменяв адрес, а не состав; E1.1 добавил 11 типов `org.ipro.form.action` —"
                + " контракт действия; E1.5 добавил `ReadOnlyReason` — причину режима просмотра"
                + " карточки; E1.6a добавил исполнителя прикладного действия, его реестр и снимок"
                + " списка; E1.6b убрала сквозной SPI тулбара ListFormToolbarContributor —"
                + " печать списка стала объявленным действием, а не швом формы; E1.7 убрала"
                + " четыре типа легаси-команд (ListCommand, его контекст, реестр и адаптер) —"
                + " их доступность считалась предикатом в UI, её вытеснило решение; E2.1 добавила"
                + " 10 типов `org.ipro.form.link` — грамматику адреса (вид маршрута, сам маршрут,"
                + " кодек), каталог опубликованных адресов с причинами неадресуемости, генератор"
                + " ссылок и результат генерации; E2.1 добавила также адресный вход решения"
                + " (`RouteLinkability`) в `org.ipro.form.action`, affordance «скопировать ссылку»"
                + " (`CopyLinkButton`) и один код его встраивания в подвал карточки"
                + " (`ItemFormLinkAffordance`); E2.2 добавила исход открытия по адресу"
                + " (`OpenResult`) и сам route-вход (`FormRouteOpener`); E2.3 добавила host адреса"
                + " и его мост с вкладками (`RouteStatePage`, `FormRouteUrlBridge`) вместе с"
                + " швом для проверки моста без браузера (`BrowserHistory`); доделки ревью E2"
                + " добавили базовый путь развёртывания (`ApplicationBasePath`); E3.0 добавила адрес"
                + " типа Explorer (`EntityExplorerAddress`) — итого 125")
            .hasSize(125);
        assertThat(withoutRole)
            .as("тип будущего platform-vaadin без роли: классификация — решение, которое нужно"
                + " принять до переноса, а не после него")
            .isEmpty();
        assertThat(stale)
            .as("в реестре есть запись о типе, которого нет в дереве: решение описывает"
                + " несуществующий код")
            .isEmpty();
    }

    @Test
    void registrySectionsAgreeWithTheirPerRoleBudget() {
        Map<String, Integer> declared = new LinkedHashMap<>();
        Map<String, Integer> actual = new LinkedHashMap<>();
        Map<String, String> clashes = new LinkedHashMap<>();
        int overlayRows = 0;
        String current = null;

        for (String line : lines(REGISTRY)) {
            Matcher section = SECTION.matcher(line);
            if (section.matches()) {
                current = section.group(1);
                declared.put(current, Integer.valueOf(section.group(2)));
                continue;
            }
            if (OVERLAY_ROW.matcher(line).matches()) {
                overlayRows++;
                continue;
            }
            Matcher entry = ENTRY.matcher(line);
            if (!entry.matches()) {
                continue;
            }
            String role = entry.group(1);
            actual.merge(role, 1, Integer::sum);
            if (current != null && !current.equals(role)) {
                clashes.put(entry.group(2), role + " под заголовком " + current);
            }
        }

        for (String role : ROLES) {
            // Роль с нулём записей — измеренный факт, а не пропуск раздела: бюджет обязан
            // совпадать с числом записей, включая нулевой. MODULE_API был пуст до D3.5.8.
            actual.putIfAbsent(role, 0);
        }

        assertThat(declared.keySet())
            .as("разделы реестра обязаны называть ровно четыре роли и overlay ссылок тестов")
            .containsExactlyInAnyOrder("APP_API", "APP_SPI", "MODULE_API", "INTERNAL", OVERLAY_SECTION);
        assertThat(actual.keySet())
            .as("запись с ролью вне словаря — незакрытая развилка, а не решение")
            .containsExactlyInAnyOrderElementsOf(ROLES);
        assertThat(clashes)
            .as("запись под чужим заголовком: бюджет раздела перестаёт быть проверяемым")
            .isEmpty();
        assertThat(actual)
            .as("число записей под заголовком обязано совпадать с бюджетом в нём")
            .isEqualTo(declared.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(OVERLAY_SECTION))
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                    Map::putAll));
        assertThat(overlayRows)
            .as("число записей overlay обязано совпадать с бюджетом раздела: иначе overlay"
                + " начинает расти тихо, а именно рост он и запрещает")
            .isEqualTo(declared.get(OVERLAY_SECTION));
    }

    /**
     * D3.5.8: API closure. Тип, объявленный {@code INTERNAL}, не может появляться в публичной
     * сигнатуре {@code APP_API}/{@code APP_SPI} — иначе правило «internal можно менять свободно»
     * нарушается молча: изменение внутреннего класса ломает приложение, которому этот тип виден
     * через API. Правило и его форма взяты у прецедента {@code platform-core} (D3.3) сознательно:
     * две разные трактовки «публичной сигнатуры» в одном репозитории означали бы, что заборы
     * проверяют разные свойства под одним названием.
     */
    @Test
    void internalTypesAreNotExposedInApplicationFacingSignatures() {
        Registry registry = readRegistry();
        ClassLoader loader = PlatformVaadinSurfaceTest.class.getClassLoader();

        Map<String, Set<String>> violations = new TreeMap<>();
        for (Map.Entry<String, String> entry : registry.roles().entrySet()) {
            if (!entry.getValue().equals("APP_API") && !entry.getValue().equals("APP_SPI")) {
                continue;
            }
            Class<?> type = load(entry.getKey(), loader);
            Set<String> signatureTypes = new TreeSet<>();
            collectSignatureTypes(type, signatureTypes);
            for (String referenced : signatureTypes) {
                String owner = ownerOf(referenced);
                if ("INTERNAL".equals(registry.roles().get(owner))) {
                    violations.computeIfAbsent(entry.getKey(), ignored -> new TreeSet<>()).add(referenced);
                }
            }
        }

        assertThat(violations)
            .as("публичная сигнатура APP_API/APP_SPI называет INTERNAL-тип: обещание"
                + " совместимости распространилось на реализацию. Либо тип обязан подняться до"
                + " MODULE_API/APP_API (если приложение или другой модуль вправе его знать), либо"
                + " ссылку надо закрыть — менять роль типа, а не правило")
            .isEmpty();
    }

    /**
     * Приложение не должно называть тип, у которого роль {@code INTERNAL}, — кроме тех ссылок,
     * что уже заморожены записью {@code legacy-internal} D1-реестра (для {@code MODULE_API}).
     */
    @Test
    void applicationMainCodeDoesNotReachIntoInternalModuleTypes() {
        Registry registry = readRegistry();
        Map<String, String> d1 = readApplicationRegistry();

        Map<String, String> offenders = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : usages(registry, APPLICATION_MAIN_SOURCES,
                Set.of("MODULE_API", "INTERNAL")).entrySet()) {
            String role = registry.roles().get(entry.getKey());
            boolean frozen = "legacy-internal".equals(d1.get(entry.getKey()));
            if (role.equals("MODULE_API") && frozen) {
                continue;
            }
            offenders.put(entry.getKey(), role + " <- " + entry.getValue());
        }

        assertThat(offenders)
            .as("production-код приложения называет тип модуля, у которого роль INTERNAL или"
                + " MODULE_API. Внутренний тип приложение знать не должно; для MODULE_API ссылка"
                + " допустима только как зарегистрированный долг — записью legacy-internal в"
                + " D1-реестре, а не молчанием")
            .isEmpty();
    }

    /**
     * Согласованность двух реестров на одних типах. D1 отвечает на вопрос «что приложение делает
     * с типом», реестр модуля — «что платформа обещает о типе». Расхождение между ними и есть
     * дефект: в D3.5.7 ровно так нашлись 10 строк, где тип числился внутренним, будучи публичным
     * API, — и нашлись они ручной сверкой, а не сборкой.
     */
    @Test
    void moduleRolesAgreeWithTheApplicationUsageRegistry() {
        Set<String> moduleTypes = new TreeSet<>(readRegistry().roles().keySet());
        Map<String, String> d1 = readApplicationRegistry();

        Map<String, String> disagreements = new TreeMap<>();
        for (Map.Entry<String, String> entry : d1.entrySet()) {
            if (!moduleTypes.contains(entry.getKey())) {
                continue;
            }
            String role = readRegistry().roles().get(entry.getKey());
            boolean consistent = switch (entry.getValue()) {
                case "API" -> role.equals("APP_API");
                case "SPI" -> role.equals("APP_SPI");
                default -> role.equals("MODULE_API") || role.equals("INTERNAL");
            };
            if (!consistent) {
                disagreements.put(entry.getKey(), "D1 " + entry.getValue() + " против роли модуля " + role);
            }
        }

        assertThat(disagreements)
            .as("роль типа в D1-реестре и в реестре модуля разошлась: одна из двух записей"
                + " перестала описывать факт. Решение принимается явно (и записывается в реестр),"
                + " а не оставляется расхождением двух заборов, каждый из которых зелёный")
            .isEmpty();

        Set<String> stale = new TreeSet<>();
        for (String fqn : d1.keySet()) {
            boolean moduleType = MODULE_PACKAGE_PREFIXES.stream().anyMatch(fqn::startsWith);
            if (moduleType && !moduleTypes.contains(fqn)) {
                stale.add(fqn);
            }
        }
        assertThat(stale)
            .as("D1-реестр называет тип в пакетах модуля, которого в реестре модуля нет: либо"
                + " запись устарела, либо тип вернулся в дерево приложения")
            .isEmpty();
    }

    /**
     * Overlay ссылок тестов приложения: ортогонален роли и только сокращается.
     *
     * <p>Тест приложения вправе пробиваться внутрь глубже, чем прикладной код, но это остаётся
     * долгом: запись фиксирует <b>точный</b> набор файлов, поэтому новая ссылка ломает сборку, а
     * снятая обязана исчезнуть из реестра.</p>
     *
     * <p><b>Полнота считается от реестра ролей, а не от ключей overlay.</b> У прецедента
     * {@code platform-core} проверка «untracked» выводится из ключей самого overlay и потому
     * вакуумна: ссылка теста на невыписанный INTERNAL-тип не ломала бы сборку. Копировать
     * прецедент вместе с этим свойством — значит получить забор, который выглядит строже, чем
     * он есть.</p>
     */
    @Test
    void applicationTestReferencesToInternalOrModuleTypesAreFrozenPerFile() {
        Registry registry = readRegistry();
        Map<String, Set<String>> actual = usages(registry, APPLICATION_TEST_SOURCES,
            Set.of("MODULE_API", "INTERNAL"));

        Set<String> untracked = new TreeSet<>(actual.keySet());
        untracked.removeAll(registry.testUsage().keySet());
        assertThat(untracked)
            .as("тест приложения ссылается на не-API тип модуля, которого нет в overlay: ссылка"
                + " обязана быть названа явно, иначе она появилась незаметно")
            .isEmpty();

        Set<String> stale = new TreeSet<>(registry.testUsage().keySet());
        stale.removeAll(actual.keySet());
        assertThat(stale)
            .as("overlay называет тип, который тесты приложения больше не трогают: запись обязана"
                + " исчезнуть — overlay только сокращается")
            .isEmpty();

        for (Map.Entry<String, Set<String>> entry : registry.testUsage().entrySet()) {
            String role = registry.roles().get(entry.getKey());
            assertThat(role)
                .as("overlay без базовой роли: запись ортогональна роли, но не заменяет её")
                .isNotNull();
            assertThat(role)
                .as("тип с ролью %s не может держать доступ из тестов приложения: тест проверяет"
                    + " поддерживаемый API, а не реализацию", role)
                .isNotIn("APP_API", "APP_SPI");
            assertThat(entry.getValue())
                .as("у записи overlay обязан быть зафиксирован непустой набор файлов, иначе"
                    + " заморозка «новая ссылка запрещена» не работает")
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
    }

    /** Production-типы модуля: по одному на файл, как их публикует артефакт. */
    private static Set<String> productionTypes() {
        Set<String> types = new TreeSet<>();
        for (Path root : MODULE_ROOTS) {
            for (Path source : javaSources(root)) {
                Matcher matcher = PACKAGE.matcher(read(source));
                if (!matcher.find()) {
                    throw new IllegalStateException("нет package: " + source);
                }
                String name = source.getFileName().toString();
                types.add(matcher.group(1) + "."
                    + name.substring(0, name.length() - ".java".length()));
            }
        }
        return types;
    }

    /**
     * Файлы, называющие типы с перечисленными ролями. Поиск по <b>простому имени</b>, а не по
     * импортам: ссылка на тип из того же пакета импорта не требует — именно так split package
     * и прятал единственную оставшуюся утечку (тест в пакете модуля обращался к
     * package-private {@code RowDraft}).
     */
    private static Map<String, Set<String>> usages(Registry registry, Path root, Set<String> roles) {
        Map<String, Set<String>> usages = new TreeMap<>();
        List<Path> sources = javaSources(root);
        for (Map.Entry<String, String> entry : registry.roles().entrySet()) {
            if (!roles.contains(entry.getValue())) {
                continue;
            }
            String simple = entry.getKey().substring(entry.getKey().lastIndexOf('.') + 1);
            Set<String> files = new TreeSet<>();
            for (Path source : sources) {
                if (mentions(read(source), simple)) {
                    files.add(root.relativize(source).toString().replace('\\', '/'));
                }
            }
            if (!files.isEmpty()) {
                usages.put(entry.getKey(), files);
            }
        }
        return usages;
    }

    private static boolean mentions(String code, String simpleName) {
        return Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b")
            .matcher(withoutCommentsAndLiterals(code)).find();
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
                    && clazz.getName().startsWith("org.ipro.")) {
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

    /** Owner для binary-имени вложенного типа: роль наследуется (§5 правил классификации). */
    private static String ownerOf(String className) {
        int nested = className.indexOf('$');
        return nested < 0 ? className : className.substring(0, nested);
    }

    private static Class<?> load(String className, ClassLoader loader) {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("production-тип platform-vaadin не найден на classpath: "
                + className + " — значит модуль не установлен, и проверять его поверхность нечего", e);
        }
    }

    private record Registry(Map<String, String> roles, Map<String, Set<String>> testUsage) {
    }

    private static Registry readRegistry() {
        Map<String, String> roles = new LinkedHashMap<>();
        Map<String, Set<String>> testUsage = new LinkedHashMap<>();
        for (String line : lines(REGISTRY)) {
            Matcher overlay = OVERLAY_ROW.matcher(line);
            if (overlay.matches()) {
                Set<String> files = overlay.group(2) == null
                    ? new LinkedHashSet<>()
                    : new LinkedHashSet<>(List.of(overlay.group(2).trim().split("\\s+")));
                if (testUsage.put(overlay.group(1), files) != null) {
                    throw new IllegalStateException("два test-usage для одного типа: " + line);
                }
                continue;
            }
            Matcher entry = ENTRY.matcher(line);
            if (!entry.matches()) {
                continue;
            }
            String previous = roles.put(entry.group(2), entry.group(1));
            if (previous != null) {
                throw new IllegalStateException("тип записан дважды: " + entry.group(2)
                    + " (" + previous + " и " + entry.group(1) + ")");
            }
        }
        return new Registry(roles, testUsage);
    }

    /** D1-реестр: {@code <role> <fqn> [файлы]} — роль типа по употреблению приложением. */
    private static Map<String, String> readApplicationRegistry() {
        Map<String, String> roles = new LinkedHashMap<>();
        for (String line : lines(APPLICATION_REGISTRY)) {
            if (line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            roles.put(parts[1], parts[0]);
        }
        return roles;
    }

    private static List<Path> javaSources(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
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
