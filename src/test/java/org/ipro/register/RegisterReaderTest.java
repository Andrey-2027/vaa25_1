package org.ipro.register;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.ip.model.Nomenclature;
import org.ip.model.NomenclaturePrice;
import org.ipro.registerfixtures.RecorderPersistenceFixtures.RecordedPeriodicRegisterFixture;
import org.junit.jupiter.api.Test;
import org.ipro.data.EntityDataAccess;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ADR-0013 §4: читатель отвергает неверные вызовы до обращения к canonical API. Сами запросы
 * проверяет {@code NomenclaturePriceRegisterOracleTest} на базе.
 */
class RegisterReaderTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 1, 0, 0);

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

    /**
     * Точечное чтение идёт bounded-каналом: тот же полный порядок победителя, но без count.
     * Раньше тест стабил {@code list} — то есть закреплял именно тот лишний total, который Р9 убирает.
     */
    @Test
    void recordedPeriodicGetLastUsesFullWinnerOrderOnCanonicalRead() {
        var recordedReader = new RegisterReader<>(RecordedPeriodicRegisterFixture.class, access);
        var winner = new RecordedPeriodicRegisterFixture("item-a",
            new RecorderRef("fixture-document-b", 42L), 2, 11L, DAY, 120);
        when(access.first(eq(RecordedPeriodicRegisterFixture.class), any(), any(Sort.class)))
            .thenReturn(Optional.of(winner));

        assertThat(recordedReader.getLast(Map.of("itemKey", "item-a"), DAY)).hasValue(winner);

        var sort = org.mockito.ArgumentCaptor.forClass(Sort.class);
        verify(access).first(eq(RecordedPeriodicRegisterFixture.class), any(), sort.capture());
        verify(access, never()).list(eq(RecordedPeriodicRegisterFixture.class), any(), any(org.springframework.data.domain.Pageable.class));
        assertThat(sort.getValue().stream().map(Sort.Order::getProperty))
            .containsExactly("period", "recordingOrder", "lineNumber");
        assertThat(sort.getValue().stream().map(Sort.Order::getDirection))
            .containsOnly(Sort.Direction.DESC);
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
        LocalDateTime period;
        java.math.BigDecimal price;
    }
}
