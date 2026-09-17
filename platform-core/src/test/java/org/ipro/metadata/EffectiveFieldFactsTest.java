package org.ipro.metadata;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.ipro.metadata.annotation.FieldMetadata;
import org.ipro.metadata.annotation.FieldType;
import org.ipro.metadata.annotation.Lookup;
import org.ipro.metadata.annotation.RequiredMode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C4.2 (ADR-0007 §6): правила вывода effective-фактов и их origin.
 *
 * <p>Снимок модели доказывает, что значения не поехали; эти тесты доказывают, что вывод
 * вообще работает — на синтетических полях, где нет объявлений, маскирующих контракт записи.
 * Без них «выводим из Bean Validation» осталось бы заявлением: в текущей модели все
 * обязательные поля объявлены явно, и вывод не участвовал бы ни в одном факте.</p>
 */
class EffectiveFieldFactsTest {

    @Test
    void beanValidationMakesTheFieldRequiredWithoutAnyDeclaration() throws Exception {
        FieldMetadataInfo info = info("notNull");

        assertThat(info.isRequired()).isTrue();
        assertThat(info.isServerRequired()).isTrue();
        assertThat(info.getRequiredOrigin()).isEqualTo(FactOrigin.BEAN_VALIDATION);
        assertThat(info.getDiagnostics()).isEmpty();
    }

    @Test
    void jpaNullabilityIsTheSecondSourceWhenBeanValidationIsSilent() throws Exception {
        FieldMetadataInfo column = info("columnNotNull");
        assertThat(column.isRequired()).isTrue();
        assertThat(column.getRequiredOrigin()).isEqualTo(FactOrigin.JPA_MAPPING);

        FieldMetadataInfo join = info("junctionNotNull");
        assertThat(join.isRequired()).isTrue();
        assertThat(join.getRequiredOrigin()).isEqualTo(FactOrigin.JPA_MAPPING);
        assertThat(join.getDiagnostics()).isEmpty();
    }

    /**
     * Контракт записи читается у всех объявленных JPA-признаков обязательности, а не только
     * у {@code nullable = false}: {@code optional = false} у ManyToOne/OneToOne/Basic иначе
     * оставлял поле UI-optional, хотя сервер его требует.
     */
    @Test
    void everyDeclaredJpaOptionalitySourceIsRead() throws Exception {
        for (String fieldName : List.of("manyToOneRequired", "oneToOneRequired", "basicRequired")) {
            FieldMetadataInfo info = info(fieldName);
            assertThat(info.isServerRequired())
                .as("JPA-контракт %s должен делать поле обязательным", fieldName)
                .isTrue();
            assertThat(info.getRequiredOrigin())
                .as("источник обязательности %s", fieldName)
                .isEqualTo(FactOrigin.JPA_MAPPING);
            assertThat(info.getDiagnostics()).as("диагностики %s", fieldName).isEmpty();
        }
    }

    /**
     * {@code EMAIL}/{@code PASSWORD}/{@code TEXT_AREA} — поддерживаемые специализации String:
     * объявление выбирает другой компонент и не является ни конфликтом, ни избыточностью.
     * Раньше строгое равенство типов объявляло это {@code TYPE_CONFLICT} и останавливало старт.
     */
    @Test
    void stringSpecializationsAreRefinementsNotConflicts() throws Exception {
        List<String> refined = List.of("emailField", "passwordField", "textAreaField");

        for (String fieldName : refined) {
            FieldMetadataInfo info = info(fieldName);
            assertThat(info.getTypeOrigin())
                .as("тип %s объявлен явно", fieldName)
                .isEqualTo(FactOrigin.EXPLICIT);
            assertThat(info.getResolvedType())
                .as("специализация %s сохраняется, а не заменяется выводом", fieldName)
                .isNotEqualTo(FieldType.TEXT);
            assertThat(codes(info))
                .as("уточнение %s не должно давать диагностик: %s", fieldName, codes(info))
                .isEmpty();
        }
    }

    /**
     * Примитив не имеет «пустого» состояния: {@code nullable = false} на нём не создаёт
     * требования заполнить поле, и UI-обязательность из него не выводится.
     */
    @Test
    void primitiveNullabilityDoesNotMakeTheFieldRequired() throws Exception {
        FieldMetadataInfo info = info("primitiveFlag");

        assertThat(info.isServerRequired()).isFalse();
        assertThat(info.isRequired()).isFalse();
        assertThat(info.getRequiredOrigin()).isEqualTo(FactOrigin.PLATFORM_DEFAULT);
    }

    @Test
    void explicitRequiredAndOptionalAreJudgedAgainstTheWriteContract() throws Exception {
        FieldMetadataInfo redundant = info("explicitRequiredWithConstraint");
        assertThat(redundant.isRequired()).isTrue();
        assertThat(redundant.getRequiredOrigin()).isEqualTo(FactOrigin.EXPLICIT);
        assertThat(codes(redundant)).contains(MetadataDiagnosticCodes.REDUNDANT_REQUIRED);

        FieldMetadataInfo stricterUi = info("explicitRequiredWithoutConstraint");
        assertThat(stricterUi.isRequired()).isTrue();
        assertThat(codes(stricterUi))
            .contains(MetadataDiagnosticCodes.UI_REQUIRED_SERVER_OPTIONAL);

        FieldMetadataInfo forbidden = info("explicitOptionalWithConstraint");
        assertThat(forbidden.isRequired()).isFalse();
        assertThat(severity(forbidden, MetadataDiagnosticCodes.UI_OPTIONAL_SERVER_REQUIRED))
            .isEqualTo(MetadataDiagnostic.Severity.ERROR);
    }

    @Test
    void declaredTypeMustAgreeWithJavaTypeOrAssociation() throws Exception {
        FieldMetadataInfo redundant = info("textString");
        assertThat(redundant.getResolvedType()).isEqualTo(FieldType.TEXT);
        assertThat(redundant.getTypeOrigin()).isEqualTo(FactOrigin.EXPLICIT);
        assertThat(codes(redundant)).contains(MetadataDiagnosticCodes.REDUNDANT_TYPE);

        FieldMetadataInfo nonsense = info("entityOnString");
        assertThat(severity(nonsense, MetadataDiagnosticCodes.TYPE_CONFLICT))
            .isEqualTo(MetadataDiagnostic.Severity.ERROR);
    }

    @Test
    void referenceTargetComesFromTheAssociationUnlessOverridden() throws Exception {
        FieldMetadataInfo inferred = info("inferredReference");
        assertThat(inferred.hasLookup()).isTrue();
        assertThat(inferred.getLookupEntity()).isEqualTo(Target.class);
        assertThat(inferred.getReferenceOrigin()).isEqualTo(FactOrigin.JPA_MAPPING);
        assertThat(inferred.getDiagnostics()).isEmpty();

        FieldMetadataInfo redundant = info("redundantReference");
        assertThat(redundant.getLookupEntity()).isEqualTo(Target.class);
        assertThat(redundant.getReferenceOrigin()).isEqualTo(FactOrigin.EXPLICIT);
        assertThat(codes(redundant)).contains(MetadataDiagnosticCodes.REDUNDANT_LOOKUP_TARGET);

        FieldMetadataInfo conflicting = info("conflictingReference");
        assertThat(severity(conflicting, MetadataDiagnosticCodes.REFERENCE_CONFLICT))
            .isEqualTo(MetadataDiagnostic.Severity.ERROR);
    }

    // === fixtures ===

    @SuppressWarnings("unused")
    private static class Target {
    }

    @SuppressWarnings("unused")
    private static class Holder {

        @FieldMetadata(label = "Н")
        @NotNull
        private String notNull;

        @FieldMetadata(label = "К")
        @Column(nullable = false)
        private String columnNotNull;

        @FieldMetadata(label = "С")
        @ManyToOne
        @JoinColumn(nullable = false)
        private Target junctionNotNull;

        @FieldMetadata(label = "Ф")
        @Column(nullable = false)
        private boolean primitiveFlag;

        @FieldMetadata(label = "Мт")
        @ManyToOne(optional = false)
        private Target manyToOneRequired;

        @FieldMetadata(label = "Оо")
        @OneToOne(optional = false)
        private Target oneToOneRequired;

        @FieldMetadata(label = "Бо")
        @Basic(optional = false)
        private String basicRequired;

        @FieldMetadata(label = "Ем", type = FieldType.EMAIL)
        private String emailField;

        @FieldMetadata(label = "Па", type = FieldType.PASSWORD)
        private String passwordField;

        @FieldMetadata(label = "Та", type = FieldType.TEXT_AREA)
        private String textAreaField;

        @FieldMetadata(label = "И", required = RequiredMode.REQUIRED)
        @NotBlank
        private String explicitRequiredWithConstraint;

        @FieldMetadata(label = "Т", required = RequiredMode.REQUIRED)
        private String explicitRequiredWithoutConstraint;

        @FieldMetadata(label = "О", required = RequiredMode.OPTIONAL)
        @NotNull
        private String explicitOptionalWithConstraint;

        @FieldMetadata(label = "Ст", type = FieldType.TEXT)
        private String textString;

        @FieldMetadata(label = "Сс", type = FieldType.ENTITY_REFERENCE)
        private String entityOnString;

        @FieldMetadata(label = "Св")
        @ManyToOne
        private Target inferredReference;

        @FieldMetadata(label = "Сп", lookup = @Lookup(entity = Target.class))
        @ManyToOne
        private Target redundantReference;

        @FieldMetadata(label = "Ск", lookup = @Lookup(entity = Holder.class))
        @ManyToOne
        private Target conflictingReference;
    }

    private static FieldMetadataInfo info(String fieldName) throws Exception {
        Field field = Holder.class.getDeclaredField(fieldName);
        return new FieldMetadataInfo(field, field.getAnnotation(FieldMetadata.class));
    }

    private static List<String> codes(FieldMetadataInfo info) {
        return info.getDiagnostics().stream().map(MetadataDiagnostic::code).toList();
    }

    private static MetadataDiagnostic.Severity severity(FieldMetadataInfo info, String code) {
        return info.getDiagnostics().stream()
            .filter(diagnostic -> diagnostic.code().equals(code))
            .map(MetadataDiagnostic::severity)
            .findFirst()
            .orElseThrow(() -> new AssertionError("Ожидалась диагностика " + code
                + ", есть: " + codes(info)));
    }
}
