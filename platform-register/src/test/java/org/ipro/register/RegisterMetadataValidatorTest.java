package org.ipro.register;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.ipro.register.RegisterMetadataValidator.Code;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-0013 §3: каждое правило стартовой валидации регистра покрыто отдельным типом-фикстурой.
 * Фикстуры не регистрируются в persistence unit — валидатор читает только аннотации.
 */
class RegisterMetadataValidatorTest {

    private static List<Code> codes(Class<?> type) {
        return RegisterMetadataValidator.validate(type).stream()
            .map(RegisterMetadataValidator.Violation::code)
            .toList();
    }

    @Test
    void periodicRegisterWithReviewedShapeHasNoViolations() {
        assertThat(codes(ValidPeriodic.class)).isEmpty();
    }

    @Test
    void aperiodicRegisterNeedsOnlyDimensionsInTheUniqueConstraint() {
        assertThat(codes(ValidAperiodic.class)).isEmpty();
    }

    @Test
    void fieldsDeclaredInSuperclassAreFound() {
        assertThat(codes(InheritedDimension.class)).isEmpty();
    }

    @Test
    void typeWithoutDeclarationIsAProgrammingError() {
        assertThatThrownBy(() -> RegisterMetadataValidator.validate(Owner.class))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("@RegisterMetadata");
    }

    @Test
    void reportsNotEntity() {
        assertThat(codes(NotEntity.class)).containsExactly(Code.NOT_ENTITY);
    }

    @Test
    void reportsNoDimensions() {
        assertThat(codes(NoDimensions.class)).containsExactly(Code.NO_DIMENSIONS);
    }

    @Test
    void reportsNoResources() {
        assertThat(codes(NoResources.class)).containsExactly(Code.NO_RESOURCES);
    }

    @Test
    void reportsMissingKeyFieldWithoutNoisyUniqueComplaint() {
        assertThat(codes(MissingDimensionField.class)).containsExactly(Code.FIELD_MISSING);
    }

    @Test
    void reportsMissingResourceField() {
        assertThat(codes(MissingResourceField.class)).containsExactly(Code.FIELD_MISSING);
    }

    @Test
    void reportsFieldNamedTwice() {
        assertThat(codes(DuplicateName.class)).containsExactly(Code.DUPLICATE_FIELD);
    }

    @Test
    void reportsPeriodThatIsNotLocalDate() {
        assertThat(codes(StringPeriod.class)).containsExactly(Code.PERIOD_TYPE);
    }

    @Test
    void reportsNullableDimension() {
        assertThat(codes(NullableDimension.class)).containsExactly(Code.KEY_NULLABLE);
    }

    @Test
    void reportsUpdatableDimension() {
        assertThat(codes(UpdatableDimension.class)).containsExactly(Code.KEY_UPDATABLE);
    }

    @Test
    void reportsUnnamedKeyColumnWithoutNoisyUniqueComplaint() {
        assertThat(codes(UnnamedKeyColumn.class)).containsExactly(Code.KEY_COLUMN_NAME);
    }

    @Test
    void reportsKeyFieldWithoutColumnAnnotation() {
        assertThat(codes(UnannotatedPeriod.class)).containsExactly(Code.KEY_COLUMN_NAME);
    }

    @Test
    void reportsMissingUniqueConstraint() {
        assertThat(codes(NoUniqueConstraint.class)).containsExactly(Code.UNIQUE_MISSING);
    }

    @Test
    void reportsUniqueConstraintWithWrongColumnOrder() {
        assertThat(codes(WrongOrderUnique.class)).containsExactly(Code.UNIQUE_MISSING);
    }

    /** ADR-0024 §2.4: подпись операции собирается из имени регистра, поэтому имя обязательно. */
    @Test
    void optInWithoutDisplayNameIsRejected() {
        assertThat(codes(OptInWithoutName.class))
            .as("регистр включает именованную операцию, но не назвался: подпись показала бы"
                + " администратору сырой ключ ACTION:... — отказ обязан быть на старте, а не в экране")
            .containsExactly(Code.OPERATION_DISPLAY_NAME_MISSING);
        assertThat(codes(OptInWithName.class))
            .as("тот же регистр с объявленным именем корректен: правило про имя, а не про opt-in")
            .isEmpty();
        assertThat(codes(ClosedRegisterWithoutName.class))
            .as("регистр без именованных операций имени не требует: иначе правило навязывало бы"
                + " текст там, где подписи нет")
            .isEmpty();
    }

    // ---- фикстуры ----------------------------------------------------------------------

    static class Owner {
    }

    @Entity
    @Table(name = "price", uniqueConstraints =
        @UniqueConstraint(name = "uk_price", columnNames = {"owner_id", "period"}))
    @RegisterMetadata(dimensions = "owner", period = "period", resources = "price")
    static class ValidPeriodic {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false, updatable = false)
        Owner owner;
        @Column(name = "period", nullable = false, updatable = false)
        LocalDateTime period;
        BigDecimal price;
    }

    @Entity
    @Table(name = "link", uniqueConstraints =
        @UniqueConstraint(name = "uk_link", columnNames = {"owner_id"}))
    @RegisterMetadata(dimensions = "owner", resources = "flag")
    static class ValidAperiodic {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false, updatable = false)
        Owner owner;
        boolean flag;
    }

    @MappedSuperclass
    static class OwnerHolder {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false, updatable = false)
        Owner owner;
    }

    @Entity
    @Table(name = "inherited", uniqueConstraints =
        @UniqueConstraint(name = "uk_inherited", columnNames = {"owner_id"}))
    @RegisterMetadata(dimensions = "owner", resources = "flag")
    static class InheritedDimension extends OwnerHolder {
        boolean flag;
    }

    @Table(name = "price", uniqueConstraints =
        @UniqueConstraint(name = "uk_price", columnNames = {"owner_id", "period"}))
    @RegisterMetadata(dimensions = "owner", period = "period", resources = "price")
    static class NotEntity {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false, updatable = false)
        Owner owner;
        @Column(name = "period", nullable = false, updatable = false)
        LocalDateTime period;
        BigDecimal price;
    }

    @Entity
    @Table(name = "price", uniqueConstraints =
        @UniqueConstraint(name = "uk_price", columnNames = {"period"}))
    @RegisterMetadata(dimensions = {}, period = "period", resources = "price")
    static class NoDimensions {
        @Column(name = "period", nullable = false, updatable = false)
        LocalDateTime period;
        BigDecimal price;
    }

    @Entity
    @Table(name = "price", uniqueConstraints =
        @UniqueConstraint(name = "uk_price", columnNames = {"owner_id", "period"}))
    @RegisterMetadata(dimensions = "owner", period = "period", resources = {})
    static class NoResources {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false, updatable = false)
        Owner owner;
        @Column(name = "period", nullable = false, updatable = false)
        LocalDateTime period;
    }

    @Entity
    @Table(name = "price", uniqueConstraints =
        @UniqueConstraint(name = "uk_price", columnNames = {"owner_id", "period"}))
    @RegisterMetadata(dimensions = "ghost", period = "period", resources = "price")
    static class MissingDimensionField {
        @Column(name = "period", nullable = false, updatable = false)
        LocalDateTime period;
        BigDecimal price;
    }

    @Entity
    @Table(name = "price", uniqueConstraints =
        @UniqueConstraint(name = "uk_price", columnNames = {"owner_id", "period"}))
    @RegisterMetadata(dimensions = "owner", period = "period", resources = "ghost")
    static class MissingResourceField {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false, updatable = false)
        Owner owner;
        @Column(name = "period", nullable = false, updatable = false)
        LocalDateTime period;
    }

    @Entity
    @Table(name = "price", uniqueConstraints =
        @UniqueConstraint(name = "uk_price", columnNames = {"owner_id", "period"}))
    @RegisterMetadata(dimensions = "owner", period = "period", resources = {"price", "owner"})
    static class DuplicateName {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false, updatable = false)
        Owner owner;
        @Column(name = "period", nullable = false, updatable = false)
        LocalDateTime period;
        BigDecimal price;
    }

    @Entity
    @Table(name = "price", uniqueConstraints =
        @UniqueConstraint(name = "uk_price", columnNames = {"owner_id", "period"}))
    @RegisterMetadata(dimensions = "owner", period = "period", resources = "price")
    static class StringPeriod {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false, updatable = false)
        Owner owner;
        @Column(name = "period", nullable = false, updatable = false)
        String period;
        BigDecimal price;
    }

    @Entity
    @Table(name = "price", uniqueConstraints =
        @UniqueConstraint(name = "uk_price", columnNames = {"owner_id", "period"}))
    @RegisterMetadata(dimensions = "owner", period = "period", resources = "price")
    static class NullableDimension {
        @ManyToOne
        @JoinColumn(name = "owner_id", updatable = false)
        Owner owner;
        @Column(name = "period", nullable = false, updatable = false)
        LocalDateTime period;
        BigDecimal price;
    }

    @Entity
    @Table(name = "price", uniqueConstraints =
        @UniqueConstraint(name = "uk_price", columnNames = {"owner_id", "period"}))
    @RegisterMetadata(dimensions = "owner", period = "period", resources = "price")
    static class UpdatableDimension {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false)
        Owner owner;
        @Column(name = "period", nullable = false, updatable = false)
        LocalDateTime period;
        BigDecimal price;
    }

    @Entity
    @Table(name = "price", uniqueConstraints =
        @UniqueConstraint(name = "uk_price", columnNames = {"owner_id", "period"}))
    @RegisterMetadata(dimensions = "owner", period = "period", resources = "price")
    static class UnnamedKeyColumn {
        @ManyToOne(optional = false)
        @JoinColumn(nullable = false, updatable = false)
        Owner owner;
        @Column(name = "period", nullable = false, updatable = false)
        LocalDateTime period;
        BigDecimal price;
    }

    @Entity
    @Table(name = "price", uniqueConstraints =
        @UniqueConstraint(name = "uk_price", columnNames = {"owner_id", "period"}))
    @RegisterMetadata(dimensions = "owner", period = "period", resources = "price")
    static class UnannotatedPeriod {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false, updatable = false)
        Owner owner;
        LocalDateTime period;
        BigDecimal price;
    }

    @Entity
    @Table(name = "price")
    @RegisterMetadata(dimensions = "owner", period = "period", resources = "price")
    static class NoUniqueConstraint {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false, updatable = false)
        Owner owner;
        @Column(name = "period", nullable = false, updatable = false)
        LocalDateTime period;
        BigDecimal price;
    }

    /** Opt-in смены ключа без имени регистра: единственный отказ — отсутствующее имя. */
    @Entity
    @Table(name = "opt_in_no_name", uniqueConstraints =
        @UniqueConstraint(name = "uk_opt_in_no_name", columnNames = {"owner_id"}))
    @RegisterMetadata(dimensions = "owner", resources = "flag", keyChangeAllowed = true)
    static class OptInWithoutName {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false)
        Owner owner;
        boolean flag;
    }

    /** Та же декларация с именем: имя — единственное, чего не хватало. */
    @Entity
    @Table(name = "opt_in_with_name", uniqueConstraints =
        @UniqueConstraint(name = "uk_opt_in_with_name", columnNames = {"owner_id"}))
    @RegisterMetadata(dimensions = "owner", resources = "flag", keyChangeAllowed = true,
        displayName = "Проверочный регистр")
    static class OptInWithName {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false)
        Owner owner;
        boolean flag;
    }

    /** Именованных операций нет — имя не требуется. */
    @Entity
    @Table(name = "closed_no_name", uniqueConstraints =
        @UniqueConstraint(name = "uk_closed_no_name", columnNames = {"owner_id"}))
    @RegisterMetadata(dimensions = "owner", resources = "flag")
    static class ClosedRegisterWithoutName {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false, updatable = false)
        Owner owner;
        boolean flag;
    }

    @Entity
    @Table(name = "price", uniqueConstraints =
        @UniqueConstraint(name = "uk_price", columnNames = {"period", "owner_id"}))
    @RegisterMetadata(dimensions = "owner", period = "period", resources = "price")
    static class WrongOrderUnique {
        @ManyToOne(optional = false)
        @JoinColumn(name = "owner_id", nullable = false, updatable = false)
        Owner owner;
        @Column(name = "period", nullable = false, updatable = false)
        LocalDateTime period;
        BigDecimal price;
    }
}
