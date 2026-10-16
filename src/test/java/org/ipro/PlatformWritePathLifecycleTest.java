package org.ipro;

import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Гейт перечня путей записи сущностей платформы и их lifecycle-обязательства.
 *
 * <p><b>Зачем.</b> ADR-0005 обещает, что {@code EntityLifecycle}-хуки вызываются на всех
 * стандартных путях сохранения. Обещание уже один раз разошлось с машиной: путь набора владельца
 * писал строки напрямую, и правила строки молча не исполнялись, пока это не нашлось замером
 * (ADR-0025). Находилось это приёмкой конкретного пути — то есть после того, как путь написан.
 * Здесь проверяется обратное направление: <b>путь не может появиться, не объявив</b>, вызывает ли
 * он хуки или почему нет. Гейт не заменяет приёмку семантики (ровно один раз на строку, порядок
 * относительно валидации) — он держит <b>полноту перечня</b>, отсутствие которой и делало дефект
 * ADR-0025 незаметным.</p>
 *
 * <p><b>Предмет перечня — два пересекающихся множества.</b> Пути записи: классы
 * production-кода платформенных модулей ({@code platform-*}), из кода которых идёт вызов мутации
 * состояния — {@code JPA} ({@code EntityManager.persist/merge/remove}), {@code BULK}
 * ({@code executeUpdate} JPQL/native: обходит и хуки, и валидацию), {@code SPRING_DATA}
 * ({@code save/saveAll/delete…} на репозитории), {@code JDBC} ({@code update/batchUpdate/execute}).
 * И исполнители правил: классы, вызывающие хуки {@code EntityLifecycleRegistry}. Второе множество
 * нужно именно потому, что первое неполно по построению: {@code RegisterKeyChanges} меняет строку
 * dirty checking'ом — вызова {@code merge} в его байткоде нет, а путь записи есть. Поэтому
 * {@code FLUSH} (принудительная синхронизация контекста) тоже сигнал: класс, который её делает,
 * пишет.</p>
 *
 * <p>Определение — по байткоду (ArchUnit), а не по тексту файла: комментарий или javadoc «путь
 * записи» путём записи не становится, fully-qualified вызов и лямбда ловятся, а {@code Map.remove}
 * на чужом контуре — нет.</p>
 *
 * <p><b>Вердикты.</b> {@code HOOKS} — путь исполняет правила: вызывает хуки реестра для того, что
 * пишет; проверяется байткодом, поэтому «объявил» и «вызывает» здесь одно и то же.
 * {@code DELEGATES} — сам не пишет, а передаёт запись объявленному пути (цель называется явно, и
 * класс обязан от неё зависеть). {@code HOSTED} — пишет как механизм, а обязательство lifecycle
 * несёт вызывающий путь (цель обязана быть объявлена как HOOKS и обязана зависеть от механизма).
 * {@code EXCEPTION} — пишет состояние, к которому контракт lifecycle не относится: технические
 * сущности платформы, строки блокировок, журнальные таблицы, самопроверки. Каждая строка обязана
 * назвать причину <b>и решение</b>: «исключение» здесь значит «названо и обосновано», а не
 * «забыто».</p>
 *
 * <p><b>Почему реестр, а не правило ArchUnit.</b> «Вызывает ли путь хуки» — не запрет на
 * зависимость, а утверждение о поведении: {@code platform-numbering} пишет счётчик нумерации, и
 * права на это не потеряет. Правило вида «все, кто пишет, зависят от lifecycle» было бы ложью
 * (технические сущности платформы живут без прикладных правил) либо запретом записи вообще.
 * Разбираемый перечень — тот же приём, что у {@code platform-public-surface.txt} и
 * {@code platform-api-baseline}: состав виден в диффе и сверяется машиной.</p>
 *
 * <p><b>Граница.</b> Гейт смотрит платформенные модули. Приложение пишет через платформенные пути
 * (canonical-сохранение, протоколы регистра), и его собственные обходы — предмет прикладной
 * проверки, а не этой. Запись в журнальные таблицы телеметрии в перечень входит и обязана
 * объявляться: это не сущности JPA, но вопрос тот же — кто пишет и почему без правил.</p>
 */
class PlatformWritePathLifecycleTest {

    private static final Path REGISTRY = Path.of("src/test/resources/platform-write-paths.txt");

    private static final String SEPARATOR = " — ";

    private static final String DECISION_SEPARATOR = "; решение: ";

    /** Хуки, которыми путь объявляет исполнение прикладных правил. */
    private static final Set<String> HOOK_METHODS = Set.of(
        "beforeSave", "beforeUpdate", "beforeDelete", "onSave", "beforeAggregateSave");

    private static final String LIFECYCLE_REGISTRY = "org.ipro.lifecycle.EntityLifecycleRegistry";

    private static final Set<String> JPA_OWNERS = Set.of(
        "jakarta.persistence.EntityManager", "org.hibernate.Session");

    private static final Set<String> JPA_MUTATIONS = Set.of("persist", "merge", "remove");

    private static final Set<String> REPOSITORY_MUTATIONS = Set.of(
        "save", "saveAll", "saveAndFlush", "saveAllAndFlush", "delete", "deleteById", "deleteAll",
        "deleteAllById", "deleteAllInBatch", "deleteInBatch");

    private static final Set<String> JDBC_MUTATIONS = Set.of("update", "batchUpdate", "execute");

    /** Контуры, у которых предмет в платформе есть сегодня: их наличие проверяется на байткоде. */
    private static final List<String> CONTOURS_WITH_SUBJECTS = List.of("JPA", "SPRING_DATA", "JDBC", "FLUSH");

    private enum Verdict {
        /** Путь вызывает хуки lifecycle для того, что пишет. */
        HOOKS,
        /** Путь пишет только через другой объявленный путь. */
        DELEGATES,
        /** Путь пишет как механизм, обязательство lifecycle несёт вызывающий путь. */
        HOSTED,
        /** Путь пишет состояние, к которому контракт lifecycle не относится; причина обязательна. */
        EXCEPTION
    }

    /** Объявленный путь: вердикт, причина и решение — всё в одной reviewed-строке реестра. */
    private record Declaration(Verdict verdict, String module, String type, String target,
                               String reason, String decision, int line) {
    }

    /** Найденный байткодом путь записи вместе с тем, чем именно он пишет. */
    private record WritePath(String module, String type, Set<String> signals, Set<String> contours) {
    }

    private record Signal(String contour, String signature) {
    }

    /**
     * Разбор байткода — один на весь класс: импорт всех {@code org.ipro} классов недёшев, а
     * проверки смотрят на один и тот же материал. Классы платформы приходят из артефактов
     * модулей, поэтому перечень не зависит от того, лежит ли модуль в этом дереве исходниками.
     */
    private static final class Bytecode {

        private static final List<String> MODULES = platformModules();

        private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.ipro");

        private static final Map<String, WritePath> WRITE_PATHS = discoverWritePaths(CLASSES, MODULES);

        /** Модуль каждого исполнителя правил: он же — подсказка с разбираемой строкой реестра. */
        private static final Map<String, String> HOOK_CALLER_MODULES =
            discoverHookCallers(CLASSES, MODULES);

        private static final Set<String> HOOK_CALLERS = new TreeSet<>(HOOK_CALLER_MODULES.keySet());

        /** Предмет перечня: кто пишет и кто исполняет правила — объявить нужно и то, и другое. */
        private static final Set<String> SUBJECTS = subjects();
    }

    @Test
    void everyPlatformEntityWritePathAndRuleExecutorIsDeclared() {
        Map<String, Declaration> declared = readRegistry();
        List<String> undeclared = new ArrayList<>();

        for (String subject : Bytecode.SUBJECTS) {
            if (declared.containsKey(subject)) {
                continue;
            }
            WritePath path = Bytecode.WRITE_PATHS.get(subject);
            if (path == null) {
                undeclared.add("  " + subject + " вызывает хуки lifecycle, но в перечне его нет:"
                    + " путь, исполняющий правила, обязан быть объявлен как HOOKS\n"
                    + "    строка для реестра: " + suggestedLine(subject,
                        Bytecode.HOOK_CALLER_MODULES.getOrDefault(subject, "<модуль>")));
            } else {
                undeclared.add("  " + path.type() + " (" + path.module() + ") пишет: "
                    + String.join(", ", path.signals()) + "\n    строка для реестра: "
                    + suggestedLine(path));
            }
        }

        assertThat(undeclared)
            .as("новый путь записи сущностей обязан объявить, вызывает ли он lifecycle-хуки, или"
                + " назвать причину исключения: иначе правило снова может молча не исполниться,"
                + " как это было на пути набора владельца (ADR-0025). Реестр — %s", REGISTRY)
            .isEmpty();

        List<String> stale = declared.values().stream()
            .filter(declaration -> !Bytecode.SUBJECTS.contains(declaration.type()))
            .map(declaration -> "  " + declaration.type() + " (строка " + declaration.line()
                + ") больше не пишет и не вызывает хуков — объявление без предмета")
            .toList();

        assertThat(stale)
            .as("реестр держится на машине, а не на памяти: строка, у которой больше нет предмета,"
                + " обязана уйти вместе с кодом, иначе гейт охраняет перечень, которого нет")
            .isEmpty();
    }

    @Test
    void hookCallersAndHooksDeclarationsAgree() {
        Set<String> declaredAsHooks = readRegistry().values().stream()
            .filter(declaration -> declaration.verdict() == Verdict.HOOKS)
            .map(Declaration::type)
            .collect(Collectors.toCollection(TreeSet::new));

        assertThat(Bytecode.HOOK_CALLERS)
            .as("вердикт HOOKS и вызов реестра хуков обязаны совпадать в обе стороны: путь,"
                + " исполняющий правила, объявлен как HOOKS, а объявленный HOOKS — действительно"
                + " их вызывает. Расхождение означает либо забытое объявление, либо объявление,"
                + " за которым нет исполнения правил")
            .containsExactlyElementsOf(declaredAsHooks);

        assertThat(Bytecode.HOOK_CALLERS)
            .as("проверка не должна быть вакуумной: пути, исполняющие прикладные правила,"
                + " обязаны находиться")
            .isNotEmpty();
    }

    @Test
    void declarationsCarryAReasonADecisionAndTheirVerdictIsTrue() {
        Map<String, Declaration> declared = readRegistry();
        List<String> problems = new ArrayList<>();

        for (Declaration declaration : declared.values()) {
            WritePath path = Bytecode.WRITE_PATHS.get(declaration.type());
            if (path == null && !Bytecode.HOOK_CALLERS.contains(declaration.type())) {
                continue; // Объявление без предмета называет отдельная проверка.
            }
            if (path != null && !path.module().equals(declaration.module())) {
                problems.add("  " + declaration.type() + " (строка " + declaration.line()
                    + "): модуль в реестре " + declaration.module() + ", а класс лежит в "
                    + path.module());
            }
            if (declaration.target() == null) {
                if (declaration.verdict() != Verdict.DELEGATES && declaration.verdict() != Verdict.HOSTED) {
                    continue;
                }
                problems.add("  " + declaration.type() + " (строка " + declaration.line() + "): "
                    + declaration.verdict() + " обязан назвать цель через «→ <класс>»");
                continue;
            }
            if (!declared.containsKey(declaration.target())) {
                problems.add("  " + declaration.type() + " (строка " + declaration.line()
                    + "): цель " + declaration.target() + " сама не объявлена — ссылка на"
                    + " необъявленный путь ничего не доказывает");
                continue;
            }
            if (declaration.verdict() == Verdict.DELEGATES
                    && !dependsOn(declaration.type(), declaration.target())) {
                problems.add("  " + declaration.type() + " (строка " + declaration.line()
                    + "): объявлено делегирование в " + declaration.target()
                    + ", но класс от неё не зависит");
            }
            if (declaration.verdict() == Verdict.HOSTED) {
                Declaration host = declared.get(declaration.target());
                if (host.verdict() != Verdict.HOOKS) {
                    problems.add("  " + declaration.type() + " (строка " + declaration.line()
                        + "): HOSTED называет несущий путь " + declaration.target()
                        + ", а он объявлен как " + host.verdict() + ", а не HOOKS: обязательство"
                        + " lifecycle не может лежать на пути, который правил не исполняет");
                } else if (!dependsOn(declaration.target(), declaration.type())) {
                    problems.add("  " + declaration.type() + " (строка " + declaration.line()
                        + "): объявлено, что обязательство несёт " + declaration.target()
                        + ", но он от механизма не зависит — значит, вызывает его кто-то другой");
                }
            }
        }

        assertThat(problems)
            .as("вердикт обязан быть проверяемым: причина и решение берутся из реестра, а не из"
                + " намерения автора; DELEGATES и HOSTED обязаны указывать на объявленный путь,"
                + " и ссылка обязана подтверждаться зависимостью в байткоде")
            .isEmpty();
    }

    @Test
    void detectionClassifiesEveryPersistenceContour() {
        assertThat(contourOf("jakarta.persistence.EntityManager", "persist", false))
            .as("JPA-мутация — путь записи").contains(new Signal("JPA", "EntityManager.persist"));
        assertThat(contourOf("org.hibernate.Session", "remove", false))
            .as("тот же контур через Hibernate-сессию").contains(new Signal("JPA", "Session.remove"));
        assertThat(contourOf("jakarta.persistence.Query", "executeUpdate", true))
            .as("bulk-запись обходит и хуки, и валидацию — её нельзя не видеть")
            .contains(new Signal("BULK", "Query.executeUpdate"));
        assertThat(contourOf("org.ipro.settings.SettingValueRepository", "save", true))
            .as("репозиторий Spring Data").contains(new Signal("SPRING_DATA", "SettingValueRepository.save"));
        assertThat(contourOf("org.springframework.jdbc.core.JdbcTemplate", "batchUpdate", false))
            .as("JDBC-запись журнальных таблиц").contains(new Signal("JDBC", "JdbcTemplate.batchUpdate"));
        assertThat(contourOf("jakarta.persistence.EntityManager", "flush", false))
            .as("flush — сигнал пути записи: dirty checking пишет без вызова merge")
            .contains(new Signal("FLUSH", "EntityManager.flush"));

        assertThat(contourOf("java.util.Map", "remove", true))
            .as("чужой контур: коллекция — не путь записи сущностей").isEmpty();
        assertThat(contourOf("org.ipro.settings.SettingsService", "save", false))
            .as("метод с именем save у не-репозитория сигналом не является").isEmpty();
        assertThat(contourOf("jakarta.persistence.EntityManager", "refresh", false))
            .as("refresh перечитывает состояние, а не пишет").isEmpty();

        Set<String> present = Bytecode.WRITE_PATHS.values().stream()
            .flatMap(path -> path.contours().stream())
            .collect(Collectors.toCollection(TreeSet::new));

        assertThat(present)
            .as("если детектор перестанет видеть контур, у которого есть предмет, перечень"
                + " останется зелёным, а путь записи через этот контур — незамеченным:"
                + " классификатор проверяется выше, наличие предмета — здесь. BULK сегодня"
                + " предмета не имеет: bulk-записи в платформе нет, и требовать её наличия"
                + " значило бы держать гейт красным из-за отсутствующего кода")
            .containsAll(CONTOURS_WITH_SUBJECTS);

        assertThat(Bytecode.MODULES)
            .as("проверка не должна быть вакуумной: модули платформы обязаны находиться")
            .isNotEmpty();

        assertThat(Bytecode.HOOK_CALLERS)
            .as("canonical-запись — путь, на котором обещание ADR-0005 держится сегодня,"
                + " и он обязан быть виден перечню")
            .contains("org.ipro.data.CanonicalWriteExecutor");
    }

    // === обнаружение ===

    private static Map<String, WritePath> discoverWritePaths(JavaClasses classes, List<String> modules) {
        Map<String, WritePath> paths = new LinkedHashMap<>();
        for (JavaClass type : classes) {
            JavaClass owner = topLevel(type);
            Optional<String> module = moduleOf(owner, modules);
            if (module.isEmpty()) {
                continue;
            }
            Set<String> signals = new TreeSet<>();
            Set<String> contours = new TreeSet<>();
            for (JavaCall<?> call : type.getMethodCallsFromSelf()) {
                signalOf(call).ifPresent(signal -> {
                    signals.add(signal.signature());
                    contours.add(signal.contour());
                });
            }
            if (signals.isEmpty()) {
                continue;
            }
            WritePath previous = paths.get(owner.getName());
            Set<String> mergedSignals = previous == null ? signals : merge(previous.signals(), signals);
            Set<String> mergedContours = previous == null ? contours : merge(previous.contours(), contours);
            paths.put(owner.getName(), new WritePath(module.get(), owner.getName(),
                mergedSignals, mergedContours));
        }
        return paths;
    }

    /**
     * Владелец записи — класс-владелец исходника: вложенный класс идёт частью своего владельца.
     *
     * <p>Иначе гейт требовал бы отдельных строк на {@code RegisterRecords$ForRecorder} и любую
     * другую внутреннюю реализацию, то есть дробил бы путь записи по тому, как он разложен по
     * классам, а не по тому, кто несёт обязательство lifecycle. Заодно это не даёт внутреннему
     * классу спрятать запись внутри класса, объявленного как исключение: сигналы собираются в
     * строку владельца, а не отдельной строкой.</p>
     */
    private static JavaClass topLevel(JavaClass type) {
        JavaClass current = type;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }

    private static Set<String> merge(Set<String> first, Set<String> second) {
        Set<String> merged = new TreeSet<>(first);
        merged.addAll(second);
        return merged;
    }

    private static Optional<Signal> signalOf(JavaCall<?> call) {
        return contourOf(call.getTargetOwner().getName(), call.getName(),
            call.getTargetOwner().isInterface());
    }

    /**
     * Правило классификации вызова: контур записи или пусто. Вынесено из обхода байткода, потому
     * что его нужно проверять отдельно — контур без предмета (сегодня это {@code BULK}) иначе
     * остался бы непроверенным правилом, а непроверенное правило и есть будущая дыра.
     */
    private static Optional<Signal> contourOf(String owner, String name, boolean ownerIsInterface) {
        if (JPA_OWNERS.contains(owner) && JPA_MUTATIONS.contains(name)) {
            return Optional.of(new Signal("JPA", simple(owner) + "." + name));
        }
        if (JPA_OWNERS.contains(owner) && name.equals("flush")) {
            return Optional.of(new Signal("FLUSH", simple(owner) + ".flush"));
        }
        if (name.equals("executeUpdate") && (owner.startsWith("jakarta.persistence.")
                || owner.startsWith("org.hibernate."))) {
            return Optional.of(new Signal("BULK", simple(owner) + ".executeUpdate"));
        }
        if (REPOSITORY_MUTATIONS.contains(name) && ownerIsInterface
                && (owner.startsWith("org.springframework.data.repository")
                    || owner.endsWith("Repository"))) {
            return Optional.of(new Signal("SPRING_DATA", simple(owner) + "." + name));
        }
        if (owner.startsWith("org.springframework.jdbc") && JDBC_MUTATIONS.contains(name)) {
            return Optional.of(new Signal("JDBC", simple(owner) + "." + name));
        }
        return Optional.empty();
    }

    /**
     * Классы, действительно вызывающие хуки реестра — то есть исполняющие прикладные правила,
     * вместе с модулем каждого: модуль нужен и для проверки объявления, и для подсказки в отказе.
     */
    private static Map<String, String> discoverHookCallers(JavaClasses classes, List<String> modules) {
        Map<String, String> moduleByCaller = new LinkedHashMap<>();
        for (JavaClass type : classes) {
            JavaClass owner = topLevel(type);
            Optional<String> module = moduleOf(owner, modules);
            if (module.isEmpty()) {
                continue;
            }
            boolean callsHooks = type.getMethodCallsFromSelf().stream().anyMatch(call ->
                call.getTargetOwner().getName().equals(LIFECYCLE_REGISTRY)
                    && HOOK_METHODS.contains(call.getName()));
            if (callsHooks) {
                moduleByCaller.put(owner.getName(), module.get());
            }
        }
        return moduleByCaller;
    }

    private static Set<String> subjects() {
        Set<String> subjects = new TreeSet<>(Bytecode.HOOK_CALLERS);
        subjects.addAll(Bytecode.WRITE_PATHS.keySet());
        return subjects;
    }

    private static boolean dependsOn(String type, String target) {
        for (JavaClass javaClass : Bytecode.CLASSES) {
            if (javaClass.getName().equals(type)) {
                return javaClass.getDirectDependenciesFromSelf().stream()
                    .anyMatch(dependency -> dependency.getTargetClass().getName().equals(target));
            }
        }
        return false;
    }

    /** Модуль класса по раскладке источника; имена модулей сверяются от длинного к короткому. */
    private static Optional<String> moduleOf(JavaClass type, List<String> modules) {
        String location = type.getSource().map(source -> source.getUri())
            .map(URI::toString).orElse("");
        return modules.stream().filter(module -> location.contains("/" + module)).findFirst();
    }

    private static List<String> platformModules() {
        try (Stream<Path> entries = Files.list(Path.of("."))) {
            return entries.filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith("platform-"))
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(name -> name))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // === реестр ===

    private static Map<String, Declaration> readRegistry() {
        List<String> lines = readLines();
        Map<String, Declaration> declarations = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();

        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int lineNumber = index + 1;
            String[] halves = line.split(SEPARATOR, 2);
            if (halves.length != 2) {
                problems.add("  строка " + lineNumber + ": нет «" + SEPARATOR.strip()
                    + "» между объявлением и причиной");
                continue;
            }
            String[] fields = halves[0].strip().split("\\s+");
            if (fields.length < 3) {
                problems.add("  строка " + lineNumber
                    + ": ожидается «<ВЕРДИКТ> <модуль> <класс> [→ <цель>]»");
                continue;
            }
            Verdict verdict;
            try {
                verdict = Verdict.valueOf(fields[0]);
            } catch (IllegalArgumentException unknown) {
                problems.add("  строка " + lineNumber + ": неизвестный вердикт " + fields[0]
                    + " (ожидается " + Set.of(Verdict.values()) + ")");
                continue;
            }
            String target = null;
            if (fields.length == 5 && fields[3].equals("→")) {
                target = fields[4];
            } else if (fields.length == 4 && fields[3].startsWith("→")) {
                target = fields[3].substring(1);
            } else if (fields.length != 3) {
                problems.add("  строка " + lineNumber + ": лишние поля после класса");
                continue;
            }
            String[] tail = halves[1].split(DECISION_SEPARATOR, 2);
            if (tail.length != 2 || tail[0].isBlank() || tail[1].isBlank()) {
                problems.add("  строка " + lineNumber + ": причина обязана называть решение («"
                    + DECISION_SEPARATOR.strip() + "<ссылка>»): исключение без обоснования —"
                    + " это забытое правило, а не названное");
                continue;
            }
            Declaration previous = declarations.put(fields[2], new Declaration(
                verdict, fields[1], fields[2], target, tail[0].strip(), tail[1].strip(), lineNumber));
            if (previous != null) {
                problems.add("  строка " + lineNumber + ": " + fields[2] + " объявлен дважды");
            }
        }

        assertThat(problems)
            .as("реестр путей записи разбирается машиной: сломанная строка — это потерянное"
                + " объявление, а потерянное объявление и есть дыра, которую гейт закрывает")
            .isEmpty();

        assertThat(declarations)
            .as("пустой реестр сделал бы проверку вакуумной")
            .isNotEmpty();

        return declarations;
    }

    /**
     * Строка-заготовка: инструмент называет класс и модуль, а вердикт и ссылку на решение ставит
     * человек. Подсказка даёт форму строки целиком, чтобы автор выбирал решение, а не вспоминал
     * формат реестра: формат, который приходится восстанавливать по чужому примеру, ломается
     * на первой же правке.
     */
    private static String suggestedLine(String subject, String module) {
        return verdictChoice() + " " + module + " " + subject + SEPARATOR
            + "<почему это не расхождение с ADR-0005>" + DECISION_SEPARATOR + "<ссылка на решение>";
    }

    private static String suggestedLine(WritePath path) {
        return suggestedLine(path.type(), path.module());
    }

    private static String verdictChoice() {
        return "<" + List.of(Verdict.values()).stream().map(Enum::name)
            .collect(Collectors.joining("|")) + ">";
    }

    private static List<String> readLines() {
        if (!Files.exists(REGISTRY)) {
            return List.of();
        }
        try {
            return Files.readAllLines(REGISTRY, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String simple(String fqcn) {
        int separator = fqcn.lastIndexOf('.');
        return separator < 0 ? fqcn : fqcn.substring(separator + 1);
    }
}
