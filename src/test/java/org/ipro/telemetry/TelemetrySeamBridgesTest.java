package org.ipro.telemetry;

import org.ipro.telemetry.api.DeclaredNameSource;
import org.ipro.telemetry.api.SqlStatementAudit;
import org.ipro.telemetry.core.DeclaredNameBridge;
import org.ipro.telemetry.core.SqlStatementAuditBridge;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * D2 → D3 (пара `telemetry` + `rls`): два нейтральных шва, которыми вывернут цикл.
 *
 * <p>Проверяются два свойства, ради которых швы заведены:</p>
 *
 * <ol>
 *   <li><b>Наблюдение не требует принуждения.</b> Без установленной канарейки RLS инспектор
 *       стейтментов обязан молча ничего не делать — иначе телеметрия зависела бы от RLS
 *       ровно так же, как до выворота, только через {@code null}-проверку.</li>
 *   <li><b>Снятие адресное.</b> Тесты живут в одной JVM и делят статику, поэтому закрытие
 *       соседнего Spring-контекста не должно обезоруживать ещё живой. Первая версия моста
 *       имён держала одну ячейку и стирала её на любом {@code destroy()} — это уронило
 *       {@code InstanceNamePilotIT} в общем прогоне, хотя в одиночку тот же тест проходил.</li>
 * </ol>
 *
 * <p>Само собой, рядом живут другие контексты и их регистрации, поэтому проверки
 * сформулированы относительно: «мои наблюдатели получили ровно это», «после снятия моей
 * регистрации стало на столько же меньше», а не «список пуст».</p>
 */
class TelemetrySeamBridgesTest {

    @Test
    void auditingIsSilentWhileNothingIsInstalled() {
        assertThatCode(() -> SqlStatementAuditBridge.audit("select 1"))
            .doesNotThrowAnyException();
        assertThatCode(() -> SqlStatementAuditBridge.audit(null))
            .doesNotThrowAnyException();
    }

    @Test
    void everyInstalledAuditorSeesTheStatementAndOnlyItsOwnRegistrationIsRemoved() {
        int installedBefore = SqlStatementAuditBridge.installedCount();
        List<String> first = new ArrayList<>();
        List<String> second = new ArrayList<>();
        SqlStatementAudit firstAudit = first::add;
        SqlStatementAudit secondAudit = second::add;

        SqlStatementAuditBridge.install(firstAudit);
        SqlStatementAuditBridge.install(secondAudit);
        SqlStatementAuditBridge.audit("select 1");

        assertThat(first).containsExactly("select 1");
        assertThat(second).containsExactly("select 1");

        SqlStatementAuditBridge.uninstall(firstAudit);
        SqlStatementAuditBridge.audit("select 2");

        assertThat(first).containsExactly("select 1");
        assertThat(second).containsExactly("select 1", "select 2");

        SqlStatementAuditBridge.uninstall(secondAudit);
        assertThat(SqlStatementAuditBridge.installedCount()).isEqualTo(installedBefore);
    }

    @Test
    void declaredNameSourcesAreAskedNewestFirstAndUnknownTypesFallThrough() {
        int installedBefore = DeclaredNameBridge.installedCount();
        DeclaredNameSource older = entity -> "старое";
        DeclaredNameSource newer = entity -> null; // тип не объявлен — решает предыдущий источник

        DeclaredNameBridge.install(older);
        DeclaredNameBridge.install(newer);

        assertThat(DeclaredNameBridge.declaredName("что угодно")).isEqualTo("старое");

        DeclaredNameBridge.uninstall(newer);
        assertThat(DeclaredNameBridge.declaredName("что угодно")).isEqualTo("старое");

        DeclaredNameBridge.uninstall(older);
        assertThat(DeclaredNameBridge.installedCount()).isEqualTo(installedBefore);
        assertThat(DeclaredNameBridge.declaredName("что угодно")).isNotEqualTo("старое");
    }
}
