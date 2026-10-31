package org.ip.archive;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S8: архив UDR читается после удаления модели отчёта.
 *
 * <p>Проверяются настоящие файлы из `docs/architecture/evidence/r2-udr-json-samples`,
 * а не синтетическая строка: если архив перестанет открываться, это будет видно
 * до R3, а не после. Отдельно фиксируются отказы — неизвестная версия, битая
 * ссылка родителя, COLUMN без поля запроса: читатель должен называть причину, а
 * не возвращать «пустой» шаблон.</p>
 */
class UdrTemplateArchiveTest {

    private static final Path SAMPLES = Path.of("docs/architecture/evidence/r2-udr-json-samples");

    @Test
    void everyArchivedTemplateIsReadableAndKeepsItsStructure() throws IOException {
        List<Path> files;
        try (var stream = Files.list(SAMPLES)) {
            files = stream.filter(path -> path.toString().endsWith(".json")).sorted().toList();
        }

        assertThat(files).as("архивные примеры — часть S8, а не временный каталог")
                .hasSize(6);
        List<UdrTemplateArchive.TemplateSummary> summaries = files.stream()
                .map(path -> {
                    try {
                        return UdrTemplateArchive.read(path);
                    } catch (IOException failure) {
                        throw new AssertionError("не удалось прочитать " + path, failure);
                    }
                })
                .toList();

        assertThat(summaries).extracting(UdrTemplateArchive.TemplateSummary::name)
                .containsExactlyInAnyOrder("Form первая", "434434", "435435",
                        "СпецификацииПоЖурналу", "Тест1", "Проь");
        assertThat(summaries).allSatisfy(summary -> {
            assertThat(summary.querySource()).isEqualTo("MANUAL");
            assertThat(summary.bands()).isNotEmpty();
        });
    }

    @Test
    void nestedGroupsKeepTheirParentReferencesAndEntityParameter() throws IOException {
        UdrTemplateArchive.TemplateSummary summary =
                UdrTemplateArchive.read(SAMPLES.resolve("04-specs-by-journal.json"));

        assertThat(summary.params()).containsExactly("parJournal");
        assertThat(summary.bands()).filteredOn(band -> band.parentKey() != null)
                .extracting(UdrTemplateArchive.BandSummary::kind)
                .contains("GROUP_HEADER", "GROUP_FOOTER");
        assertThat(summary.bands()).flatExtracting(UdrTemplateArchive.BandSummary::fields)
                .anySatisfy(field -> assertThat(field).contains("COUNT"));
        assertThat(summary.orders()).isEmpty();
        assertThat(summary.selfParentBands())
                .as("старая модель записала parent_id = id у двух корневых групп; архив сохраняет факт")
                .isEqualTo(2);
    }

    @Test
    void printBindingSurvivesTheArchive() throws IOException {
        UdrTemplateArchive.TemplateSummary summary =
                UdrTemplateArchive.read(SAMPLES.resolve("01-form-pervaya.json"));

        assertThat(summary.targetEntityClass()).isEqualTo("org.ip.model.UnitOfMeasurement");
        assertThat(summary.params()).containsExactly("param1");
        assertThat(summary.bands()).hasSize(1);
        assertThat(summary.fieldCount()).isEqualTo(3);
    }

    @Test
    void unknownFormatIsRejected() {
        assertThatThrownBy(() -> UdrTemplateArchive.parse(template("other-format", 3)))
                .isInstanceOf(UdrTemplateArchive.ArchiveFormatException.class)
                .hasMessageContaining("формат");
    }

    @Test
    void unsupportedVersionIsRejected() {
        assertThatThrownBy(() -> UdrTemplateArchive.parse(template(UdrTemplateArchive.FORMAT, 2)))
                .isInstanceOf(UdrTemplateArchive.ArchiveFormatException.class)
                .hasMessageContaining("версия схемы: 2");
    }

    @Test
    void danglingParentKeyIsRejected() {
        String json = """
                {"format":"ipro-report-template","schemaVersion":3,"template":{
                  "name":"битый","jpql":"select 1","querySource":"MANUAL","maxRows":10,"timeoutMs":1000,
                  "params":[],"orders":[],
                  "bands":[{"key":"1","parentKey":"9","kind":"GROUP_HEADER","position":0,
                            "fields":[]}]}}
                """;

        assertThatThrownBy(() -> UdrTemplateArchive.parse(json))
                .isInstanceOf(UdrTemplateArchive.ArchiveFormatException.class)
                .hasMessageContaining("parentKey 9");
    }

    @Test
    void columnWithoutQueryFieldIsRejected() {
        String json = """
                {"format":"ipro-report-template","schemaVersion":3,"template":{
                  "name":"битый","jpql":"select 1","querySource":"MANUAL","maxRows":10,"timeoutMs":1000,
                  "params":[],"orders":[],
                  "bands":[{"key":"1","kind":"DETAIL","position":0,
                            "fields":[{"kind":"COLUMN","aggregation":"NONE","alignment":"LEFT"}]}]}}
                """;

        assertThatThrownBy(() -> UdrTemplateArchive.parse(json))
                .isInstanceOf(UdrTemplateArchive.ArchiveFormatException.class)
                .hasMessageContaining("без queryField");
    }

    private static String template(String format, int version) {
        return """
                {"format":"%s","schemaVersion":%d,"template":{
                  "name":"минимальный","jpql":"select 1","querySource":"MANUAL","maxRows":10,"timeoutMs":1000,
                  "params":[],"bands":[],"orders":[]}}
                """.formatted(format, version);
    }
}
