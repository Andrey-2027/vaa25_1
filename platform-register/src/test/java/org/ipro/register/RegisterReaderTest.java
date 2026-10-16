package org.ipro.register;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.ipro.data.EntityDataAccess;
import org.ipro.registerfixtures.RecorderPersistenceFixtures.PeriodicRegisterFixture;
import org.ipro.registerfixtures.RecorderPersistenceFixtures.RecordedPeriodicRegisterFixture;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
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
 *
 * <p>D-R S5(в): тест больше не опирается на прикладные сущности ({@code Nomenclature},
 * {@code NomenclaturePrice}) — предмет проверки читателя не в них, а в форме декларации регистра.
 * Роль прикладного периодического регистра играет модульная фикстура
 * {@link PeriodicRegisterFixture} (измерение {@code itemKey}, период {@code period}), а неверные
 * декларации объявлены здесь же: они описывают ровно те дефекты, которые читатель обязан ловить,
 * и не притворяются прикладной моделью. Поэтому приёмкой теста стала сборка самого модуля
 * (план §6.5), а не сборка приложения.</p>
 */
class RegisterReaderTest {

    private static final LocalDateTime DAY = LocalDateTime.of(2026, 10, 1, 0, 0);

    /** Измерение модульной фикстуры: имя берётся из её декларации, а не из прикладной модели. */
    private static final String DIMENSION = "itemKey";

    private final EntityDataAccess access = mock(EntityDataAccess.class);
    private final RegisterReader<PeriodicRegisterFixture> reader =
        new RegisterReader<>(PeriodicRegisterFixture.class, access);

    @Test
    void rejectsTypeWithoutDeclaration() {
        assertThatThrownBy(() -> new RegisterReader<>(Undeclared.class, access))
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
            .hasMessageContaining("не хватает [" + DIMENSION + "]");
        verifyNoInteractions(access);
    }

    @Test
    void rejectsUnexpectedDimension() {
        Map<String, Object> values = new HashMap<>();
        values.put(DIMENSION, "item-a");
        values.put("warehouse", "main");

        assertThatThrownBy(() -> reader.getLast(values, DAY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("лишние [warehouse]");
        verifyNoInteractions(access);
    }

    @Test
    void rejectsNullDimensionValue() {
        Map<String, Object> values = new HashMap<>();
        values.put(DIMENSION, null);

        assertThatThrownBy(() -> reader.getLast(values, DAY))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'" + DIMENSION + "' не задано");
        verifyNoInteractions(access);
    }

    @Test
    void rejectsNullDate() {
        assertThatThrownBy(() -> reader.getLast(Map.of(DIMENSION, "item-a"), null))
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

        assertThat(recordedReader.getLast(Map.of(DIMENSION, "item-a"), DAY)).hasValue(winner);

        var sort = org.mockito.ArgumentCaptor.forClass(Sort.class);
        verify(access).first(eq(RecordedPeriodicRegisterFixture.class), any(), sort.capture());
        verify(access, never()).list(eq(RecordedPeriodicRegisterFixture.class), any(), any(org.springframework.data.domain.Pageable.class));
        assertThat(sort.getValue().stream().map(Sort.Order::getProperty))
            .containsExactly("period", "recordingOrder", "lineNumber");
        assertThat(sort.getValue().stream().map(Sort.Order::getDirection))
            .containsOnly(Sort.Direction.DESC);
    }

    // ---- фикстуры ----------------------------------------------------------------------

    /** Тип без декларации: отказ приходит из валидатора до чтения JPA-mapping'а. */
    static class Undeclared {
    }

    /** Непериодический регистр: читателю «последней записи на дату» он не подходит. */
    @Entity
    @Table(name = "reader_link", uniqueConstraints =
        @UniqueConstraint(name = "uk_reader_link", columnNames = {"owner_id"}))
    @RegisterMetadata(dimensions = "owner", resources = "flag")
    static class Link {
        @Column(name = "owner_id", nullable = false, updatable = false)
        String owner;
        boolean flag;
    }

    /** Периодический регистр без unique-ключа: декларация нарушена, и это видно на конструкторе. */
    @Entity
    @Table(name = "reader_broken")
    @RegisterMetadata(dimensions = "owner", period = "period", resources = "price")
    static class Broken {
        @Column(name = "owner_id", nullable = false, updatable = false)
        String owner;
        @Column(name = "period_date", nullable = false, updatable = false)
        LocalDateTime period;
        BigDecimal price;
    }
}
