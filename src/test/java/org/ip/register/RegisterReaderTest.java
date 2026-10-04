package org.ip.register;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.ip.model.Nomenclature;
import org.ip.model.NomenclaturePrice;
import org.junit.jupiter.api.Test;
import org.ipro.data.EntityDataAccess;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ADR-0013 §4: читатель отвергает неверные вызовы до обращения к canonical API. Сами запросы
 * проверяет {@code NomenclaturePriceRegisterOracleTest} на базе.
 */
class RegisterReaderTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);

    private final EntityDataAccess access = mock(EntityDataAccess.class);
    private final RegisterReader<NomenclaturePrice> reader = new RegisterReader<>(NomenclaturePrice.class, access);

    @Test
    void rejectsTypeWithoutDeclaration() {
        assertThatThrownBy(() -> new RegisterReader<>(Nomenclature.class, access))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("@RegisterMetadata");
    }

    @Test
    void rejectsAperiodicRegister() {
        assertThatThrownBy(() -> new RegisterReader<>(Link.class, access))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("периодический");
    }

    @Test
    void rejectsBrokenDeclarationAtConstruction() {
        assertThatThrownBy(() -> new RegisterReader<>(Broken.class, access))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("UNIQUE_MISSING");
    }

    @Test
    void rejectsMissingDimension() {
        assertThatThrownBy(() -> reader.getLast(Map.of(), DAY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("не хватает [nomenclature]");
        verifyNoInteractions(access);
    }

    @Test
    void rejectsUnexpectedDimension() {
        Map<String, Object> values = new HashMap<>();
        values.put("nomenclature", new Nomenclature());
        values.put("warehouse", "main");

        assertThatThrownBy(() -> reader.getLast(values, DAY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("лишние [warehouse]");
        verifyNoInteractions(access);
    }

    @Test
    void rejectsNullDimensionValue() {
        Map<String, Object> values = new HashMap<>();
        values.put("nomenclature", null);

        assertThatThrownBy(() -> reader.getLast(values, DAY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'nomenclature' не задано");
        verifyNoInteractions(access);
    }

    @Test
    void rejectsNullDate() {
        assertThatThrownBy(() -> reader.getLast(Map.of("nomenclature", new Nomenclature()), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("asOf");
        verifyNoInteractions(access);
    }

    // ---- фикстуры ----------------------------------------------------------------------

    @Entity
    @Table(name = "reader_link", uniqueConstraints =
        @UniqueConstraint(name = "uk_reader_link", columnNames = {"owner_id"}))
    @RegisterMetadata(dimensions = "owner", resources = "flag")
    static class Link {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false, updatable = false)
        Nomenclature owner;
        boolean flag;
    }

    @Entity
    @Table(name = "reader_broken")
    @RegisterMetadata(dimensions = "owner", period = "period", resources = "price")
    static class Broken {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false, updatable = false)
        Nomenclature owner;
        @Column(name = "period_date", nullable = false, updatable = false)
        LocalDate period;
        java.math.BigDecimal price;
    }
}
