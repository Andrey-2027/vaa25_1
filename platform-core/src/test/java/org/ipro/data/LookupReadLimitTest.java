package org.ipro.data;

import org.ipro.crud.EntityLookup;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D3.5.3: граница лимита lookup — часть контракта, а не пожелание в javadoc.
 *
 * <p>{@code EntityLookup} обещает «без безусловного {@code findAll}», но один параметр
 * {@code limit} это обещание не держал: {@code Integer.MAX_VALUE} в вызове — та же выгрузка
 * таблицы. Проверка стоит в единственной точке построения lookup-запроса, поэтому действует и для
 * будущих потребителей.</p>
 */
class LookupReadLimitTest {

    @Test
    void limitAboveContractBoundaryIsRejected() {
        assertThatThrownBy(() -> LookupRead.of(String.class, List.of("code"), "term",
            EntityLookup.MAX_LIMIT + 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exceeds")
            .hasMessageContaining("list-read");
    }

    @Test
    void boundaryItselfIsAccepted() {
        LookupRead<String> request = LookupRead.of(String.class, List.of("code"), "term",
            EntityLookup.MAX_LIMIT);

        assertThat(request.limit()).isEqualTo(EntityLookup.MAX_LIMIT);
    }

    @Test
    void negativeLimitStaysRejected() {
        assertThatThrownBy(() -> LookupRead.of(String.class, List.of("code"), "term", -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("negative");
    }

    /** Полная выборка объявляется явно: unpaged list — не lookup с большим лимитом. */
    @Test
    void unboundedReadIsNotExpressibleAsLookup() {
        assertThatThrownBy(() -> LookupRead.of(String.class, List.of(), "", Integer.MAX_VALUE))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
