package org.ipro.reportstudio.query;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Служебные параметры запуска (parEntity/parEntityId/parEntityIds): раскрытие
 * нотации {@code :parEntity.id} и исключение из тестовых параметров редактора.
 */
class ServiceParamsTest {

    @Test
    void entityIdPathIsExpanded() {
        assertThat(ServiceParams.expand(
            "select s.codeSpec from PrdSpec s where s.journal.id = :parEntity.id"))
            .isEqualTo("select s.codeSpec from PrdSpec s where s.journal.id = :parEntityId");
    }

    @Test
    void entityIdPathWithWhitespaceIsExpanded() {
        assertThat(ServiceParams.expand("where u.id = :parEntity . id"))
            .isEqualTo("where u.id = :parEntityId");
    }

    @Test
    void plainEntityParamIsLeftUntouched() {
        assertThat(ServiceParams.expand("where u = :parEntity"))
            .isEqualTo("where u = :parEntity");
    }

    @Test
    void entityIdsListItemIsLeftUntouched() {
        assertThat(ServiceParams.expand("where u.id in (:parEntityIds)"))
            .isEqualTo("where u.id in (:parEntityIds)");
    }

    @Test
    void alreadyExpandedIdIsLeftUntouched() {
        assertThat(ServiceParams.expand("where u.id = :parEntityId"))
            .isEqualTo("where u.id = :parEntityId");
    }

    @Test
    void nonServicePathNodeIsLeftUntouched() {
        assertThat(ServiceParams.expand("where u.id = :other.id"))
            .isEqualTo("where u.id = :other.id");
    }

    @Test
    void nullAndBlankAreSafe() {
        assertThat(ServiceParams.expand(null)).isNull();
        assertThat(ServiceParams.expand("  ")).isEqualTo("  ");
    }

    @Test
    void serviceNamesAreRecognized() {
        assertThat(ServiceParams.isServiceName("parEntity")).isTrue();
        assertThat(ServiceParams.isServiceName("parEntityId")).isTrue();
        assertThat(ServiceParams.isServiceName("parEntityIds")).isTrue();
        assertThat(ServiceParams.isServiceName("journal")).isFalse();
        assertThat(ServiceParams.isServiceName(null)).isFalse();
    }

    @Test
    void namesConstantMatchesMembers() {
        assertThat(ServiceParams.NAMES).isEqualTo(Set.of("parEntity", "parEntityId", "parEntityIds"));
    }
}