package org.ipro;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2: слой контрактов — отдельный артефакт, и приложение обязано оставаться его потребителем.
 *
 * <p><b>Что здесь осталось и почему.</b> Разбор D1/D2 указал, что проверки вынесенного модуля
 * жили в дереве приложения, а манифест ставил модуль с {@code -DskipTests}: артефакт можно было
 * опубликовать, не запустив ни одной его проверки. Состав среза, его чистота и набор
 * зависимостей переехали к владельцу — {@code platform-contracts/src/test}
 * ({@code ContractsModuleCompositionTest}).</p>
 *
 * <p>Здесь остались только свойства, которые видит <b>обе стороны сразу</b>, и ни одна из сторон
 * не может проверить их одна:</p>
 * <ol>
 * <li>у каждого типа контракта ровно один дом — копия в дереве приложения сделала бы границу
 *     декоративной (компилятор возьмёт локальный класс);</li>
 * <li>пакеты, разделённые между артефактами (split package), перечислены явно — список
 *     расширяется только вместе с решением;</li>
 * <li>нейтральный identifier приходит из Java-only артефакта, а не из UI-библиотеки, и не
 *     вернулся обратно ни в приложение, ни в {@code crudui-core};</li>
 * <li>приложение действительно потребляет контракты зависимостью, а не держит их у себя.</li>
 * </ol>
 *
 * <p>Список типов и пакетов здесь не дублируется: он <b>выводится</b> из дерева модуля. Иначе
 * два reviewed-списка (модульный и прикладной) разошлись бы, и один из них стал бы ложью.</p>
 */
class PlatformContractsModuleTest {

    private static final Path MODULE = Path.of("platform-contracts");
    private static final Path MODULE_SOURCES = MODULE.resolve("src/main/java");

    /** Прикладное дерево исходников: тот же FQN здесь означал бы вторую копию контракта. */
    private static final Path APPLICATION_SOURCES = Path.of("src/main/java");

    /**
     * Пакеты, разделённые между артефактами: срез идёт по типам, поэтому часть пакетов
     * оказывается и в контрактах, и в дереве платформы. Это зафиксированное решение D2
     * (единица разреза — тип), а не незамеченное разрастание.
     */
    private static final Set<String> REVIEWED_SPLIT_PACKAGES = Set.of(
        "org.ipro.data",
        "org.ipro.fetch.plan");

    private static final String IDENTITY_ARTIFACT = "platform-identity-api";

    private static final Path UI_ARTIFACT_IDENTIFIER =
        Path.of("../crudui/crudui-core/src/main/java/org/ipro/crud/IdentifiableEntity.java");

    private static final Path IDENTITY_ARTIFACT_IDENTIFIER =
        Path.of("../crudui/platform-identity-api/src/main/java/org/ipro/identity/IdentifiableEntity.java");

    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>([^<]+)</artifactId>");

    @Test
    void everyContractTypeLivesOnlyInTheContractsModule() {
        List<String> duplicates = new ArrayList<>();
        for (Path source : javaSources(MODULE_SOURCES)) {
            String relative = MODULE_SOURCES.relativize(source).toString().replace('\\', '/');
            if (Files.exists(APPLICATION_SOURCES.resolve(relative))) {
                duplicates.add(relative);
            }
        }

        assertThat(duplicates)
            .as("у контракта обязан быть ровно один дом: копия в дереве приложения делает"
                + " границу модуля декоративной, потому что компилятор возьмёт локальный класс")
            .isEmpty();
        assertThat(javaSources(MODULE_SOURCES))
            .as("проверка не должна быть вакуумной")
            .isNotEmpty();
    }

    /**
     * Второй владелец пакета, кроме дерева приложения.
     *
     * <p>Без него гейт становится слепым именно там, где разрез и происходит: пока
     * {@code org.ipro.metadata} и {@code org.ipro.fetch} лежали в дереве, пакет
     * {@code org.ipro.fetch.plan} читался как разделённый. После D3.3 эти типы уехали в
     * {@code platform-core}, дерево их больше не содержит — и гейт, смотрящий только на дерево,
     * отрапортовал бы «split package стало меньше», хотя пакет по-прежнему делится между двумя
     * артефактами. Смена владельца не должна выглядеть как исчезновение проблемы.</p>
     */
    private static final Path[] OTHER_OWNERS = {
        APPLICATION_SOURCES,
        Path.of("platform-core/src/main/java")
    };

    @Test
    void splitPackagesAreExplicitAndReviewed() {
        Set<String> split = new TreeSet<>();
        for (Path source : javaSources(MODULE_SOURCES)) {
            String contractPackage = packageOf(source);
            for (Path owner : OTHER_OWNERS) {
                Path ownerPackage = owner.resolve(contractPackage.replace('.', '/'));
                if (Files.exists(ownerPackage) && !javaSources(ownerPackage).isEmpty()) {
                    split.add(contractPackage);
                }
            }
        }

        assertThat(split)
            .as("рез идёт по типам, поэтому часть пакетов разделена между артефактами."
                + " Список таких пакетов reviewed: расширять его — значит сознательно"
                + " увеличивать число split package в платформе; уменьшать — задача срезов")
            .isEqualTo(new TreeSet<>(REVIEWED_SPLIT_PACKAGES));
    }

    @Test
    void theIdentityContractComesFromTheNeutralArtifactAndNotFromTheUiOne() {
        assertThat(declaredArtifactIds(read(MODULE.resolve("pom.xml"))))
            .as("контракты не должны зависеть от UI-артефакта: Vaadin не тёк только за счёт"
                + " provided-scope, а направление владения от scope не меняется")
            .doesNotContain("crudui-core")
            .contains(IDENTITY_ARTIFACT);

        assertThat(Path.of(APPLICATION_SOURCES.toString(), "org/ipro/crud/IdentifiableEntity.java"))
            .as("identifier не должен вернуться в дерево приложения")
            .doesNotExist();
        assertThat(UI_ARTIFACT_IDENTIFIER)
            .as("старый дом идентификатора в UI-артефакте обязан опустеть: два класса с одним"
                + " назначением — это не граница, а две границы")
            .doesNotExist();
        assertThat(IDENTITY_ARTIFACT_IDENTIFIER)
            .as("новый дом нейтрального идентификатора — отдельный Java-only модуль реактора"
                + " crudui: у контракта два владельца, поэтому он не принадлежит ни одному")
            .exists();
    }

    @Test
    void theApplicationConsumesTheContractsAsAnArtifact() {
        Set<String> declared = declaredArtifactIds(read(Path.of("pom.xml")));

        assertThat(declared)
            .as("границу держит сборка: у модуля нет класса приложения на пути компиляции."
                + " Если приложение перестанет объявлять зависимость, типы контрактов просто"
                + " исчезнут, а соблазн вернуть их в дерево станет нормой")
            .contains("platform-contracts");
    }

    private static Set<String> declaredArtifactIds(String pom) {
        Set<String> declared = new TreeSet<>();
        Matcher matcher = ARTIFACT_ID.matcher(pom);
        while (matcher.find()) {
            declared.add(matcher.group(1));
        }
        return declared;
    }

    private static String packageOf(Path source) {
        Matcher matcher = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE).matcher(read(source));
        if (!matcher.find()) {
            throw new IllegalStateException("нет package: " + source);
        }
        return matcher.group(1);
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
