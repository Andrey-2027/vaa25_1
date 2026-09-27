package org.ip.views.reportstudio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Забор дисциплины уведомлений (D3.6.4, подшаг 4.3).
 *
 * <p>Три дефекта D3.6 — F1, F2 и F6 — это один и тот же класс ошибки: решение «правка
 * пользователя или программная синхронизация» принималось отдельно в каждом из двух десятков
 * мест. Слушатель проверял флаг, метод мутации звал уведомление, а программное заполнение обязано
 * было не забыть флаг поставить — и три раза забыло (дважды в каноническом редакторе, один раз в
 * structured; третий случай нашёлся только сравнением двух реализаций).</p>
 *
 * <p>После слияния точка уведомления одна, и guard стоит внутри неё: пока идёт
 * {@code sync}, наружу не уходит ничего. Этот забор держит утверждение по исходникам — так же,
 * как {@code ReportCatalogAssemblyGateTest} держит единственный узел сборки строки каталога.
 * Он не проверяет поведение (для этого есть наборы редактора), он проверяет, что форма кода не
 * позволяет вернуть класс ошибки обратно: второе место с {@code changeListener.run()} или второе
 * место, меняющее счётчик глубины, появиться не может незаметно.</p>
 */
class ReportStructureEditorMutationDisciplineTest {

    private static final Path EDITOR = Path.of(
            "src/main/java/org/ip/views/reportstudio/ReportStructureEditor.java");

    private static final String NOTIFICATION = "changeListener.run()";
    private static final String SINGLE_POINT = "private void edited() {";

    @Test
    void ownerIsNotifiedFromExactlyOneGuardedPlace() throws IOException {
        String source = source();

        assertThat(count(source, NOTIFICATION))
                .as("уведомление владельца — ровно одна точка: второе место означает, что guard"
                        + " придётся повторить и там, а это и есть класс ошибки F1/F2/F6")
                .isEqualTo(1);

        int singlePoint = source.indexOf(SINGLE_POINT);
        assertThat(singlePoint)
                .as("единственная точка уведомления называется edited()")
                .isGreaterThan(0);
        assertThat(source.indexOf(NOTIFICATION))
                .as("единственный вызов владельца обязан быть внутри edited()")
                .isGreaterThan(singlePoint);
        assertThat(between(source, singlePoint, source.indexOf(NOTIFICATION)))
                .as("между входом в edited() и уведомлением обязан стоять guard синхронизации")
                .contains("isSyncing()");
    }

    @Test
    void syncDepthIsOwnedByOneHelper() throws IOException {
        String source = source();

        assertThat(count(source, "syncDepth++")).isEqualTo(1);
        assertThat(count(source, "syncDepth--")).isEqualTo(1);
        assertThat(count(source, "syncDepth"))
                .as("счётчик глубины: объявление, два изменения и одно чтение в isSyncing()")
                .isEqualTo(4);
        assertThat(source)
                .as("никаких прямых присваиваний счётчику: только sync() меняет глубину")
                .doesNotContain("syncDepth =");
    }

    @Test
    void theOldBooleanFlagCannotComeBack() throws IOException {
        String source = source();

        assertThat(source)
                .as("флаг processor заменён счётчиком: boolean не выдерживает вложенных sync")
                .doesNotContain("boolean processor");
        assertThat(source)
                .as("предикат синхронизации только читается; присваивание означало бы второй владелец"
                        + " состояния")
                .doesNotContain("isSyncing() =");
    }

    private static String source() throws IOException {
        return Files.readString(EDITOR, StandardCharsets.UTF_8);
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            found++;
        }
        return found;
    }

    private static String between(String text, int from, int to) {
        return text.substring(from, Math.max(from, to));
    }
}
