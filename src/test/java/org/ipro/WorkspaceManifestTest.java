package org.ipro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
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
 * D2: манифест воркспейса — исполняемое утверждение о порядке сборки, а не JSON «для справки».
 *
 * <p>Разбор ревью D1/D2 нашёл в манифесте ровно тот класс ошибки, который в проекте принято
 * закрывать тестом, а не текстом: <b>объявленный граф не соответствовал реальному</b>.
 * `platform-contracts` компилируется против `crudui-core`, но в манифесте у него
 * `dependsOn: []`, а сам `crudui` стоял в списке двенадцатым. Скрипт бутстрапа собирал
 * проекты просто в порядке JSON, поэтому на пустом локальном репозитории первый же build step
 * падал на неразрешимой зависимости — то есть заявленная воспроизводимость не существовала.
 * Проверить это можно было только запуском скрипта в чистой среде; здесь та же проверка
 * выполняется на каждом `mvn verify`.</p>
 *
 * <p>Три свойства:</p>
 * <ol>
 * <li><b>Порядок сборки</b> — манифест обязан читаться сверху вниз: каждая зависимость
 *     объявлена раньше своего потребителя. Скрипт дополнительно сортирует проекты
 *     топологически, но манифест, порядок которого противоречит его же `dependsOn`, — это
 *     документ, вводящий в заблуждение.</li>
 * <li><b>`dependsOn` = настоящие Maven-рёбра.</b> Для каждого проекта сравниваются
 *     объявленные зависимости с workspace-артефактами, которые реально упомянуты в его
 *     pom'ах (включая pom'ы модулей реактора). Новый pom-ребёнок не может появиться молча:
 *     либо он есть в `dependsOn`, либо тест падает.</li>
 * <li><b>Fingerprints.</b> Значения in-repo проектов пересчитываются здесь же, поэтому дрейф
 *     виден в обычном прогоне, а не только при бутстрапе.</li>
 * </ol>
 *
 * <p>Про алгоритм. У in-repo проектов допускается только
 * {@code gitvaa-source-tree-sha256-v2}: порядок путей в нём задан как ordinal. Прежняя
 * версия (v1) сортировала пути culture-aware средствами PowerShell, то есть её результат
 * зависел от культуры процесса — «fingerprint совпал» означало совпадение реализации
 * проверяющего, а не файлов. v1 оставлен внешним проектам, чьи записанные значения здесь
 * никто не меняет и не проверяет: у чужих каталогов своя история, а не наша.</p>
 */
class WorkspaceManifestTest {

    private static final Path PROJECT_ROOT = Path.of("").toAbsolutePath().normalize();
    private static final Path MANIFEST = Path.of("scripts/local-dependencies.json");

    /** Единственный алгоритм, разрешённый проектам этого чекаута. */
    private static final String REPOSITORY_ALGORITHM = "gitvaa-source-tree-sha256-v2";

    private static final String IN_REPO_KIND = "in-repo-project";

    /**
     * Тот же набор исключений, что в скрипте бутстрапа: сборочный вывод и служебные каталоги
     * не являются входом артефакта.
     */
    private static final Pattern EXCLUDED_SEGMENT =
        Pattern.compile("/(target|node_modules|\\.git|\\.idea|\\.freebuff)/");

    /**
     * Явная перезапись записанных fingerprints. Не по умолчанию и не при расхождении:
     * манифест обновляют, приняв изменение, а не потому что сборка покраснела.
     */
    private static final String WRITE_PROPERTY = "platform.manifest.write";

    private static final char QUOTE = '"';

    private static final String ID_KEY = QUOTE + "id" + QUOTE + ":";
    private static final String SHA256_KEY = QUOTE + "sha256" + QUOTE + ":";
    private static final String FILE_COUNT_KEY = QUOTE + "fileCount" + QUOTE + ":";

    private static final Pattern MODULE_ENTRY = Pattern.compile("<module>([^<]+)</module>");
    private static final Pattern DEPENDENCY_MANAGEMENT =
        Pattern.compile("<dependencyManagement>.*?</dependencyManagement>", Pattern.DOTALL);
    private static final Pattern DEPENDENCY_BLOCK =
        Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
    private static final Pattern PARENT_BLOCK =
        Pattern.compile("<parent>(.*?)</parent>", Pattern.DOTALL);
    private static final Pattern COORDINATES =
        Pattern.compile("<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>");

    @Test
    void manifestOrderIsATopologicalOrderOfItsOwnDependencies() {
        JsonNode manifest = readManifest();
        Map<String, Integer> positions = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode project : manifest.get("projects")) {
            positions.put(project.get("id").asText(), index++);
        }

        List<String> problems = new ArrayList<>();
        for (JsonNode project : manifest.get("projects")) {
            String id = project.get("id").asText();
            for (JsonNode dependency : project.get("dependsOn")) {
                String dependencyId = dependency.asText();
                Integer dependencyPosition = positions.get(dependencyId);
                if (dependencyPosition == null) {
                    problems.add(id + " зависит от '" + dependencyId + "', которого нет в манифесте");
                } else if (dependencyPosition >= positions.get(id)) {
                    problems.add(id + " (позиция " + positions.get(id) + ") зависит от '"
                        + dependencyId + "' (позиция " + dependencyPosition + ")");
                }
            }
        }

        assertThat(problems)
            .as("манифест читается как порядок сборки: зависимость обязана стоять выше"
                + " потребителя. Иначе воспроизводимость бутстрапа существует только на"
                + " машине, где нужный артефакт уже установлен в локальный репозиторий")
            .isEmpty();
    }

    @Test
    void everyDeclaredDependencyMatchesTheRealMavenWorkspaceEdges() {
        JsonNode manifest = readManifest();
        Map<String, String> workspaceArtifacts = workspaceArtifacts(manifest);

        List<String> problems = new ArrayList<>();
        for (JsonNode project : manifest.get("projects")) {
            String id = project.get("id").asText();
            Set<String> declared = new TreeSet<>();
            for (JsonNode dependency : project.get("dependsOn")) {
                declared.add(dependency.asText());
            }

            Path basePath = PROJECT_ROOT.resolve(project.get("relativePath").asText()).normalize();
            if (!Files.isDirectory(basePath)) {
                continue;
            }
            Set<String> actual = referencedWorkspaceProjects(project, basePath, workspaceArtifacts, id);

            Set<String> missing = new TreeSet<>(actual);
            missing.removeAll(declared);
            Set<String> extra = new TreeSet<>(declared);
            extra.removeAll(actual);
            if (!missing.isEmpty()) {
                problems.add(id + ": pom ссылается на " + missing + ", но dependsOn их не называет"
                    + " — порядок сборки не воспроизводится на пустом локальном репозитории");
            }
            if (!extra.isEmpty()) {
                problems.add(id + ": dependsOn называет " + extra
                    + ", но pom на них не ссылается — объявленный граф разошёлся с Maven");
            }
        }

        assertThat(problems)
            .as("dependsOn — не документация, а требование к порядку сборки: он обязан совпадать"
                + " с реальными workspace-рёбрами pom'ов (включая модули реактора)")
            .isEmpty();
    }

    /**
     * Уникальность публикуемых координат.
     *
     * <p>Это не гигиена нейминга, а условие осмысленности `dependsOn`: если два проекта
     * публикуют один GAV, в локальном репозитории окажутся артефакты того, кто собрался позже,
     * и объявленный порядок перестанет что-либо определять. Проверка дублирует проверку скрипта
     * бутстрапа намеренно (как и fingerprints): расхождение двух независимых реализаций видно
     * только тогда, когда их две.</p>
     */
    @Test
    void everyPublishedCoordinateHasASingleOwner() {
        JsonNode manifest = readManifest();

        Map<String, String> owners = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (JsonNode project : manifest.get("projects")) {
            String id = project.get("id").asText();
            for (JsonNode artifact : project.get("artifacts")) {
                String[] parts = artifact.asText().split(":");
                if (parts.length < 2) {
                    problems.add(id + ": не разобрана координата " + artifact.asText());
                    continue;
                }
                String coordinate = parts[0] + ":" + parts[1];
                String previous = owners.put(coordinate, id);
                if (previous != null) {
                    problems.add(coordinate + " публикуется и '" + previous + "', и '" + id
                        + "': порядок сборки перестал бы определять, что лежит в локальном репозитории");
                }
            }
        }

        assertThat(problems)
            .as("координаты в манифесте — обещание, кто владеет артефактом. Два владельца одного"
                + " GAV делают порядок сборки неопределяющим")
            .isEmpty();
        assertThat(owners)
            .as("проверка не должна быть вакуумной: манифест обязан публиковать артефакты")
            .isNotEmpty();
    }

    @Test
    void inRepoProjectsUseTheDeterministicFingerprintAlgorithmAndMatchIt() {
        JsonNode manifest = readManifest();

        List<String> problems = new ArrayList<>();
        Map<String, Fingerprint> drifted = new LinkedHashMap<>();
        int verified = 0;
        for (JsonNode project : manifest.get("projects")) {
            String id = project.get("id").asText();
            String kind = project.get("source").get("kind").asText();
            String algorithm = project.get("fingerprint").get("algorithm").asText();
            Path basePath = PROJECT_ROOT.resolve(project.get("relativePath").asText()).normalize();

            if (IN_REPO_KIND.equals(kind)) {
                if (!REPOSITORY_ALGORITHM.equals(algorithm)) {
                    problems.add(id + " живёт в этом чекауте, но его fingerprint посчитан"
                        + " алгоритмом '" + algorithm + "': у in-repo проекта порядок путей обязан"
                        + " быть ordinal, иначе 'совпало' зависит от культуры процесса");
                }
                if (!Files.isDirectory(basePath)) {
                    problems.add(id + " объявлен in-repo, но каталога нет: " + basePath);
                    continue;
                }
            } else if (!REPOSITORY_ALGORITHM.equals(algorithm) || !Files.isDirectory(basePath)) {
                // Внешние проекты со своими записанными значениями проверяет скрипт
                // бутстрапа: там другая история дрейфа и её не наша задача переписывать.
                continue;
            }

            List<String> inputs = new ArrayList<>();
            for (JsonNode input : project.get("fingerprint").get("inputs")) {
                inputs.add(input.asText());
            }

            Fingerprint actual = fingerprint(basePath, inputs);
            String expectedHash = project.get("fingerprint").get("sha256").asText();
            int expectedCount = project.get("fingerprint").get("fileCount").asInt();
            if (!expectedHash.equals(actual.sha256()) || expectedCount != actual.fileCount()) {
                problems.add(id + ": ожидался " + expectedHash + " (" + expectedCount
                    + " файлов), фактически " + actual.sha256() + " (" + actual.fileCount()
                    + " файлов) — пересмотрите изменение и обновите манифест намеренно: -D"
                    + WRITE_PROPERTY + "=true");
                drifted.put(id, actual);
            }
            verified++;
        }

        if (!drifted.isEmpty() && Boolean.getBoolean(WRITE_PROPERTY)) {
            rewriteFingerprints(drifted);
            return;
        }

        assertThat(problems)
            .as("fingerprint — обещание, что бутстрап собирает записанные исходники. Дрейф"
                + " обязан быть виден на mvn verify, а не только при запуске скрипта")
            .isEmpty();
        assertThat(verified)
            .as("проверка не должна быть вакуумной: хотя бы in-repo проекты сверяются здесь")
            .isGreaterThanOrEqualTo(8);
    }

    /**
     * Перезапись только двух чисел в уже существующих блоках: текст правится точечно, поэтому
     * обновление fingerprint'а не превращается в переформатирование всего манифеста (а значит,
     * не прячет настоящие правки в шуме диффа).
     */
    private static void rewriteFingerprints(Map<String, Fingerprint> drifted) {
        String text = read(MANIFEST);
        for (Map.Entry<String, Fingerprint> entry : drifted.entrySet()) {
            String id = entry.getKey();
            Fingerprint actual = entry.getValue();
            text = replaceJsonValue(text, projectEnd(text, id), SHA256_KEY,
                QUOTE + actual.sha256() + QUOTE);
            text = replaceJsonValue(text, projectEnd(text, id), FILE_COUNT_KEY,
                Integer.toString(actual.fileCount()));
        }
        try {
            Files.writeString(MANIFEST, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Позиция сразу за {@code "id": "<id>"} — граница области правки. Без неё замена уехала бы
     * в чужой проект: порядок блоков в манифесте не контракт и меняется свободно.
     */
    private static int projectEnd(String text, String id) {
        String quoted = QUOTE + id + QUOTE;
        int from = 0;
        while (true) {
            int keyAt = text.indexOf(ID_KEY, from);
            if (keyAt < 0) {
                throw new IllegalStateException("В манифесте нет проекта '" + id + "'");
            }
            int valueAt = skipWhitespace(text, keyAt + ID_KEY.length());
            if (text.startsWith(quoted, valueAt)) {
                return valueAt + quoted.length();
            }
            from = keyAt + ID_KEY.length();
        }
    }

    /**
     * Точечная замена значения по ключу: правится само значение, а не файл целиком. Обновление
     * fingerprint'а не переформатирует манифест (в том числе его ручные однострочные массивы) и
     * не прячет настоящие правки в шуме диффа.
     */
    private static String replaceJsonValue(String text, int from, String key, String value) {
        int keyAt = text.indexOf(key, from);
        if (keyAt < 0) {
            throw new IllegalStateException("В манифесте нет ключа " + key + " после позиции " + from);
        }
        int valueAt = skipWhitespace(text, keyAt + key.length());
        int valueEnd;
        if (valueAt < text.length() && text.charAt(valueAt) == QUOTE) {
            valueEnd = text.indexOf(QUOTE, valueAt + 1) + 1;
        } else {
            valueEnd = valueAt;
            while (valueEnd < text.length()
                    && text.charAt(valueEnd) != ','
                    && !Character.isWhitespace(text.charAt(valueEnd))) {
                valueEnd++;
            }
        }
        return text.substring(0, valueAt) + value + text.substring(valueEnd);
    }

    private static int skipWhitespace(String text, int from) {
        int index = from;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    /**
     * Workspace-артефакты: координата {@code groupId:artifactId} → id проекта. Именно по этой
     * карте pom-ссылки превращаются в рёбра графа сборки.
     */
    private static Map<String, String> workspaceArtifacts(JsonNode manifest) {
        Map<String, String> artifacts = new LinkedHashMap<>();
        for (JsonNode project : manifest.get("projects")) {
            for (JsonNode artifact : project.get("artifacts")) {
                String[] parts = artifact.asText().split(":");
                if (parts.length < 2) {
                    throw new IllegalStateException("Не разобрана координата артефакта: " + artifact);
                }
                artifacts.put(parts[0] + ":" + parts[1], project.get("id").asText());
            }
        }
        return artifacts;
    }

    /**
     * Workspace-проекты, на которые реально ссылаются pom'ы проекта: build steps плюс модули
     * их реакторов. Ссылки внутри одного проекта (например модуль на своего родителя) в граф
     * не попадают — это не отдельный шаг сборки.
     */
    private static Set<String> referencedWorkspaceProjects(
            JsonNode project, Path basePath, Map<String, String> workspaceArtifacts, String projectId) {
        Set<Path> poms = new LinkedHashSet<>();
        for (JsonNode step : project.get("buildSteps")) {
            poms.add(basePath.resolve(step.get("pom").asText()).normalize());
        }

        Set<String> referenced = new TreeSet<>();
        for (Path pom : expandReactors(poms)) {
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            // dependencyManagement — это не зависимость, а объявление версий: вырезаем,
            // иначе родительские BOM'ы дали бы рёбра, которых в сборке нет.
            String text = DEPENDENCY_MANAGEMENT.matcher(read(pom)).replaceAll(" ");
            Matcher dependencies = DEPENDENCY_BLOCK.matcher(text);
            while (dependencies.find()) {
                collect(dependencies.group(1), workspaceArtifacts, projectId, referenced);
            }
            Matcher parents = PARENT_BLOCK.matcher(text);
            while (parents.find()) {
                collect(parents.group(1), workspaceArtifacts, projectId, referenced);
            }
        }
        return referenced;
    }

    private static void collect(
            String block, Map<String, String> workspaceArtifacts, String projectId, Set<String> referenced) {
        Matcher coordinates = COORDINATES.matcher(block);
        if (!coordinates.find()) {
            return;
        }
        String owner = workspaceArtifacts.get(coordinates.group(1) + ":" + coordinates.group(2));
        if (owner != null && !owner.equals(projectId)) {
            referenced.add(owner);
        }
    }

    /** Pom'ы модулей реактора, объявленные от корня: иначе ребро модуля осталось бы невидимым. */
    private static Set<Path> expandReactors(Set<Path> poms) {
        Set<Path> expanded = new LinkedHashSet<>(poms);
        List<Path> queue = new ArrayList<>(poms);
        while (!queue.isEmpty()) {
            Path pom = queue.remove(0);
            if (!Files.isRegularFile(pom)) {
                continue;
            }
            Path directory = pom.getParent();
            Matcher modules = MODULE_ENTRY.matcher(read(pom));
            while (modules.find()) {
                Path modulePom = directory.resolve(modules.group(1) + "/pom.xml").normalize();
                if (expanded.add(modulePom)) {
                    queue.add(modulePom);
                }
            }
        }
        return expanded;
    }

    /**
     * {@code gitvaa-source-tree-sha256-v2}: относительный путь (ordinal) + NUL + sha256 файла +
     * LF, затем sha256 от всего потока. Ровно это считает скрипт бутстрапа, поэтому значения
     * сравнимы: запись манифеста перепроверяется независимой реализацией, а не сама собой.
     */
    private static Fingerprint fingerprint(Path basePath, List<String> inputs) {
        List<Path> collected = new ArrayList<>();
        for (String input : inputs) {
            Path resolved = basePath.resolve(input).normalize();
            if (Files.isRegularFile(resolved)) {
                collected.add(resolved);
            } else if (Files.isDirectory(resolved)) {
                try (Stream<Path> files = Files.walk(resolved)) {
                    files.filter(Files::isRegularFile).forEach(collected::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else {
                throw new IllegalStateException("Fingerprint input not found: " + resolved);
            }
        }

        record Entry(String relativePath, Path file) {
        }
        List<Entry> entries = new ArrayList<>();
        for (Path file : collected) {
            String relativePath = basePath.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
            if (EXCLUDED_SEGMENT.matcher("/" + relativePath + "/").find()) {
                continue;
            }
            entries.add(new Entry(relativePath, file));
        }
        entries.sort(Comparator.comparing(Entry::relativePath));

        StringBuilder content = new StringBuilder();
        String previousPath = null;
        int fileCount = 0;
        for (Entry entry : entries) {
            if (entry.relativePath().equals(previousPath)) {
                continue;
            }
            previousPath = entry.relativePath();
            content.append(entry.relativePath()).append('\0')
                .append(sha256(readBytes(entry.file()))).append('\n');
            fileCount++;
        }

        return new Fingerprint(sha256(content.toString().getBytes(StandardCharsets.UTF_8)), fileCount);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    private static JsonNode readManifest() {
        try {
            return new ObjectMapper().readTree(Files.readString(MANIFEST, StandardCharsets.UTF_8));
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

    private static byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Fingerprint(String sha256, int fileCount) {
    }
}
