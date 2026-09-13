package org.ip.security;

import org.ip.model.ReceivingDocument;
import org.ipro.rls.RlsDimension;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.rls.RlsDimensionRegistry;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Read/write parity для сложных (custom) RLS-измерений — закрытие остатка ADX-06.
 *
 * До этого у custom-политики read-предикат не был заявлен нигде, кроме строки SQL в
 * {@code @Filter}: у стандартного измерения его выводит реестр из valuePaths и сверяет с
 * фильтром, а у custom выводить нечем. Правка фильтра и правка {@code getRlsChecks()}
 * (write intent) могли разойтись молча, и расходились в самом неприятном направлении —
 * видно меньше, чем можно менять, либо строки записи шире читаемых.
 *
 * Тесты проверяют обе половины контракта: объявленный {@code readCondition} обязателен и
 * сверяется с фактическим {@code @Filter} (негативные фикстуры), а боевой скан приложения
 * этот контракт проходит (ReceivingDocument).
 */
class RlsCustomDimensionParityTest {

    /** Положительный случай: custom-измерение объявило read-предикат, он совпадает с фильтром. */
    @Test
    void customDimensionWithDeclaredReadConditionIsAccepted() {
        RlsDimensionRegistry registry = new RlsDimensionRegistry("rlsparity.consistent");
        registry.rebuild();

        assertThat(registry.dimensions()).contains("PARITY_OK");
        assertThat(registry.kindOf("PARITY_OK")).isEqualTo(RlsDimensionKind.FILTERABLE);
    }

    /** Custom FILTERABLE без readCondition — отказ: сверять read/write не с чем. */
    @Test
    void customDimensionWithoutReadConditionFailsAtRebuild() {
        RlsDimensionRegistry registry = new RlsDimensionRegistry("rlsparity.noread");

        assertThatThrownBy(registry::rebuild)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PARITY_NO_READ")
            .hasMessageContaining("readCondition")
            .hasMessageContaining("@Filter");
    }

    /** Заявленный и фактический read-предикат разошлись — отказ, а не молчаливое расхождение. */
    @Test
    void driftingReadConditionFailsAtRebuild() {
        RlsDimensionRegistry registry = new RlsDimensionRegistry("rlsparity.mismatch");

        assertThatThrownBy(registry::rebuild)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("read/write policy mismatch")
            .hasMessageContaining("PARITY_MISMATCH")
            .hasMessageContaining("archived");
    }

    /** Стандартное измерение не может объявлять readCondition — он был бы проигнорирован. */
    @Test
    void standardDimensionWithReadConditionFailsAtRebuild() {
        RlsDimensionRegistry registry = new RlsDimensionRegistry("rlsparity.standardread");

        assertThatThrownBy(registry::rebuild)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PARITY_STANDARD_READ")
            .hasMessageContaining("не является custom");
    }

    /** CHECK_ONLY-измерение без фильтра не может объявлять read-предикат — сверять не с чем. */
    @Test
    void checkOnlyDimensionWithReadConditionFailsAtRebuild() {
        RlsDimensionRegistry registry = new RlsDimensionRegistry("rlsparity.checkonlyread");

        assertThatThrownBy(registry::rebuild)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PARITY_CHECK_ONLY_READ")
            .hasMessageContaining("нет @Filter");
    }

    /**
     * Боевой скан (то же, что делает приложение при старте) обязан пройти, и каждое
     * FILTERABLE-измерение ReceivingDocument — единственная custom-политика в проекте —
     * обязано заявлять свой read-предикат. Проверка читает именно аннотации, а не список
     * измерений реестра: важно, что intent заявлен в коде сущности, а не только что
     * реестр не упал.
     */
    @Test
    void applicationScanAcceptsCustomPoliciesAndTheyDeclareReadConditions() {
        RlsDimensionRegistry registry = new RlsDimensionRegistry("org.ip");
        registry.rebuild();

        RlsDimension[] declared = ReceivingDocument.class.getAnnotationsByType(RlsDimension.class);
        assertThat(Arrays.stream(declared)
            .filter(RlsDimension::custom)
            .filter(annotation -> annotation.kind() == RlsDimensionKind.FILTERABLE)
            .filter(annotation -> annotation.readCondition().isBlank())
            .map(RlsDimension::value))
            .as("каждое custom FILTERABLE-измерение обязано объявить readCondition")
            .isEmpty();
        assertThat(Arrays.stream(declared)
            .filter(annotation -> annotation.kind() == RlsDimensionKind.FILTERABLE)
            .map(RlsDimension::value))
            .containsExactlyInAnyOrder("JOURNAL", "BRANCH");
    }
}
