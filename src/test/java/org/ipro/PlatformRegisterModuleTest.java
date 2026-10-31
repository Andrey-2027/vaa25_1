package org.ipro;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-R S3 — границы артефакта {@code platform-register} после того, как в него приехал протокол
 * набора владельца, а прикладная конфигурация исчезла.
 *
 * <p>Проверяются пять свойств, и все они — про отношение модуля к приложению:</p>
 * <ol>
 * <li><b>Модуль не знает приложение.</b> Обратная ссылка замкнула бы цикл сборки: приложение
 *     зависит от артефакта, значит исходников {@code org.ip} в модуле быть не может.</li>
 * <li><b>Каждая срезовая конфигурация привозит только свой срез.</b> Смысл разделения монолитной
 *     конфигурации: read-конфигурация S1 не знает команд ключа (S2) и набора владельца (S3),
 *     key-write-конфигурация S2 не знает набора владельца. Проверка читает текст модуля, а не
 *     список бинов, поэтому появление имени более позднего среза видно сразу.</li>
 * <li><b>Последний срез приносит именно протокол набора владельца.</b> Процедура набора, два
 *     участника платформенных швов сохранения и удаления агрегата и заменяемый шов авторизации —
 *     и всё это в одной конфигурации, а не размазано по предыдущим.</li>
 * <li><b>В приложении не осталось ни одной регистрации регистра.</b> Каталог
 *     {@code src/main/java/org/ipro/register} пуст, запись в app imports исчезла, и ни один файл
 *     приложения не собирает движок сам. Это то, ради чего срезы делались: после S3 у движка один
 *     владелец — артефакт, и вторая сборка (та, что уже один раз разошлась с bean'ом) невозможна
 *     по раскладке, а не по договорённости.</li>
 * <li><b>Сущность порядка переехала вместе со своим владельцем.</b> Долг S1 («засев часов остаётся
 *     приложению, пока сущность принадлежит приложению») закрыт S2 переносом сущности, а S3 —
 *     переносом последнего её потребителя (набора владельца), поэтому scanning пакета сущности
 *     остаётся в модуле, а не возвращается приложению.</li>
 * </ol>
 */
class PlatformRegisterModuleTest {

    private static final Path MODULE = Path.of("platform-register");

    private static final Path MODULE_SOURCES = MODULE.resolve("src/main/java");

    private static final Path APPLICATION_SOURCES = Path.of("src/main/java");

    /** Каталог движка в приложении: после S3 он обязан быть пустым. */
    private static final Path APPLICATION_ENGINE = Path.of("src/main/java/org/ipro/register");

    private static final String IMPORTS_PATH =
        "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private static final String MODULE_IMPORTS_PATH =
        "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private static final String READ_CONFIGURATION =
        "src/main/java/org/ipro/register/config/RegisterReadConfiguration.java";

    private static final String KEY_WRITE_CONFIGURATION =
        "src/main/java/org/ipro/register/config/RegisterKeyWriteConfiguration.java";

    private static final String RECORDER_SET_CONFIGURATION =
        "src/main/java/org/ipro/register/config/RegisterRecorderSetConfiguration.java";

    private static final String ENTITY_PACKAGE = "org.ipro.register.persistence";

    /**
     * Комментарии убираются перед поиском имён: называть следующий срез в прозе — это решение
     * («что здесь появится и почему»), а называть его типом в коде — уже его привозить.
     */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\n]*");

    /** Запись по ключу (S2) — её не должен привезти срез чтения. */
    private static final List<String> KEY_WRITE_TYPES = List.of(
        "RegisterKeyChanges", "RegisterKeyWrites", "RegisterKeyChangePermissions",
        "RegisterKeyWritePermissions", "RegisterKeyWriteGuardInstaller", "RegisterRowState",
        "RegisterWriteMutex", "RegisterKeyWriteConfiguration");

    /** Протокол набора владельца (S3) — его не должен привезти ни один предыдущий срез. */
    private static final List<String> RECORDER_SET_TYPES = List.of(
        "RegisterRecords", "RecorderSetWriter", "RecorderSetWriteRequest", "RecorderSetContribution",
        "RecorderSetContributionParticipant", "RecorderSetDeleteParticipant",
        "RecorderSetTransactionExecutor", "RecorderSetWriteAuthorizer", "RecordedRegisterReader",
        "RlsRecorderSetWriteAuthorizer", "RegisterRecorderSetConfiguration");

    /** Движок целиком: после S3 в приложении не остаётся ни одного его типа. */
    private static final List<String> ENGINE_TYPES = List.of(
        "RegisterRegistry", "RegisterStartupValidator", "RegisterReader", "RegisterSliceReader",
        "RecorderTypeRegistry", "RegisterMetadataValidator", "RegisterDescriptor");

    @Test
    void theModuleNeverNamesTheApplication() {
        List<String> problems = new ArrayList<>();
        for (Path source : javaSources(MODULE_SOURCES)) {
            String text = read(source);
            if (text.contains("org.ip.") || text.contains("org.ip;")) {
                problems.add(MODULE_SOURCES.relativize(source).toString());
            }
        }

        assertThat(problems)
            .as("модуль не может ссылаться на дерево приложения: приложение зависит от артефакта,"
                + " и обратная ссылка замкнула бы цикл сборки. Найденные файлы называют org.ip")
            .isEmpty();
    }

    @Test
    void eachSliceConfigurationBringsOnlyItsOwnSlice() {
        assertThat(read(MODULE.resolve(MODULE_IMPORTS_PATH)).lines().map(String::trim).toList())
            .as("артефакт несёт ровно три авто-конфигурации — по одной на срез, и ни одной лишней:"
                + " вторая запись в одном срезе означала бы, что срез привозит не только своё")
            .containsExactlyInAnyOrder(
                "org.ipro.register.config.RegisterReadConfiguration",
                "org.ipro.register.config.RegisterKeyWriteConfiguration",
                "org.ipro.register.config.RegisterRecorderSetConfiguration");

        String readConfiguration = withoutComments(read(MODULE.resolve(READ_CONFIGURATION)));
        List<String> readLeaks = new ArrayList<>(KEY_WRITE_TYPES);
        readLeaks.addAll(RECORDER_SET_TYPES);
        assertThat(readLeaks.stream().filter(readConfiguration::contains).toList())
            .as("read-конфигурация S1 не знает ни команд ключа (S2), ни набора владельца (S3): иначе"
                + " первый же срез выноса читал бы и писал одновременно — а именно это и было главным"
                + " блокером S1 (12 из 14 ссылок за границу среза)")
            .isEmpty();

        String keyWriteConfiguration = withoutComments(read(MODULE.resolve(KEY_WRITE_CONFIGURATION)));
        assertThat(RECORDER_SET_TYPES.stream().filter(keyWriteConfiguration::contains).toList())
            .as("key-write-конфигурация S2 не знает протокола набора владельца (S3): он приезжает"
                + " своим срезом и пользуется теми же часами порядка, а не вторым механизмом")
            .isEmpty();
    }

    @Test
    void theLastSliceBringsTheOwnerSetProtocol() {
        String configuration = withoutComments(read(MODULE.resolve(RECORDER_SET_CONFIGURATION)));

        assertThat(configuration)
            .as("конфигурация S3 объявляет процедуру набора владельца и оба участия в"
                + " платформенных швах: canonical-сохранение агрегата и его удаление")
            .contains("RegisterRecords registerRecords")
            .contains("AggregateSaveContributionParticipant recorderSetContributionParticipant")
            .contains("CanonicalDeleteParticipant recorderSetDeleteParticipant")
            .contains("new RecorderSetContributionParticipant(")
            .contains("new RecorderSetDeleteParticipant(");

        assertThat(configuration)
            .as("шов авторизации остаётся заменяемым (@ConditionalOnMissingBean): ADR-0019 требует,"
                + " чтобы решение об операции строки принимал владелец, и платформенная реализация"
                + " здесь — рабочая, а не единственная возможная")
            .contains("@ConditionalOnMissingBean(RecorderSetWriteAuthorizer.class)")
            .contains("RlsRecorderSetWriteAuthorizer");
    }

    @Test
    void theApplicationKeepsNoRegisterRegistrationAtAll() {
        assertThat(Files.isDirectory(APPLICATION_ENGINE)
                ? javaSources(APPLICATION_ENGINE).stream().map(Path::toString).toList()
                : List.<String>of())
            .as("каталог движка в приложении пуст после S3: файлы переезжали, а не копировались,"
                + " потому что две копии движка — это ровно тот дефект (bean и рядом вторая сборка),"
                + " который срез S1 закрывал")
            .isEmpty();

        assertThat(read(Path.of(IMPORTS_PATH)))
            .as("приложение больше не регистрирует авто-конфигурацию регистра: привозить нечего —"
                + " чтение (S1), запись по ключу (S2) и набор владельца (S3) приехали своими"
                + " артефактами со своими записями")
            .doesNotContain("org.ipro.register");

        List<String> problems = new ArrayList<>();
        for (Path source : javaSources(APPLICATION_SOURCES)) {
            String text = withoutComments(read(source));
            if (text.contains("new RegisterSliceReader<>") || text.contains("new RegisterReader<>")
                    || text.contains("new RecorderSetWriter(") || text.contains("new RegisterRecords(")) {
                problems.add(APPLICATION_SOURCES.relativize(source).toString());
            }
            for (String engineType : ENGINE_TYPES) {
                if (text.contains("class " + engineType) || text.contains("interface " + engineType)) {
                    problems.add(APPLICATION_SOURCES.relativize(source).toString()
                        + " объявляет " + engineType);
                }
            }
        }

        assertThat(problems)
            .as("приложение не собирает движок и не объявляет его типов: срез S1 закрыл именно этот"
                + " дефект — сервис получал bean, а форма списка конструировала читатель рядом, и"
                + " расхождение двух сборок не было видно никому. Теперь вторая сборка невозможна"
                + " по раскладке")
            .isEmpty();
    }

    @Test
    void theOrderClockAndItsLastConsumerLiveInTheSameArtifact() {
        assertThat(MODULE.resolve("src/main/java/org/ipro/register/persistence/RecorderOrderClock.java"))
            .as("сущность порядка записи принадлежит модулю с S2: тип, владеющий сущностью,"
                + " не может пересечь границу модуля раньше своего persistence")
            .exists();
        assertThat(Path.of("src/main/java/org/ipro/register/persistence/RecorderOrderClock.java"))
            .doesNotExist();

        String configuration = withoutComments(read(MODULE.resolve(KEY_WRITE_CONFIGURATION)));
        assertThat(configuration)
            .as("владелец сущности объявляет и её scanning, и её засев: оставить засев приложению"
                + " значило бы оставить приложению обязательную регистрацию модуля")
            .contains("@EntityScan(\"" + ENTITY_PACKAGE + "\")")
            .contains("RecorderOrderClockBootstrap recorderOrderClockBootstrap")
            .contains("recorderOrderClockStartup")
            .contains("clocks.initialize(registers.descriptors())");

        assertThat(MODULE.resolve(RECORDER_SET_CONFIGURATION))
            .as("последний потребитель часов — набор владельца — приезжает в тот же артефакт:"
                + " оставь его приложению, и модуль обещал бы часы порядка типу, которого в модуле нет")
            .exists();
        assertThat(withoutComments(read(MODULE.resolve(RECORDER_SET_CONFIGURATION))))
            .as("набор владельца берёт часы порядка через общий мьютекс записи, а не изобретает"
                + " второй механизм нумерации строк")
            .doesNotContain("RecorderOrderClock");
    }

    private static String withoutComments(String source) {
        return LINE_COMMENT.matcher(BLOCK_COMMENT.matcher(source).replaceAll(" ")).replaceAll(" ");
    }

    private static List<Path> javaSources(Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("нет каталога исходников: " + root.toAbsolutePath());
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        if (!Files.exists(path)) {
            return "";
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
