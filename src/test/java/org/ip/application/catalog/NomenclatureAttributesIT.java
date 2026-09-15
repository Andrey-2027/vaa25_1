package org.ip.application.catalog;

import org.ip.config.DataInitializer;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.model.NomAttributeValue;
import org.ip.model.Nomenclature;
import org.ip.model.UnitOfMeasurement;
import org.ip.model.Workshop;
import org.ip.repository.AttributeTypeRepository;
import org.ip.repository.AttributeValueRepository;
import org.ip.repository.UnitOfMeasurementRepository;
import org.ip.repository.WorkshopRepository;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.ServiceLocator;
import org.ipro.data.CanonicalEntityService;
import org.ipro.crud.MetadataDrivenAggregateSaveService;
import org.ipro.crud.ValidationException;
import org.ipro.events.EventSource;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.rls.AccessGrantRepository;
import org.ipro.rls.RlsTestFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Атрибуты номенклатуры как owned section агрегата: «Атрибут — значение» сохраняются
 * одной транзакцией с шапкой, ввод формы разрешается в строки единого словаря,
 * межстрочные правила работают на границе агрегата.
 */
@SpringBootTest(classes = org.ip.Application.class)
class NomenclatureAttributesIT {

    @MockitoBean
    private DataInitializer dataInitializer;

    @Autowired
    private MetadataDrivenAggregateSaveService aggregateSaveService;

    @Autowired
    private ServiceLocator serviceLocator;

    @Autowired
    private GenericOwnedSectionService sectionService;

    @Autowired
    private SectionMetadataRegistry sectionRegistry;

    @Autowired
    private UnitOfMeasurementRepository unitRepository;

    @Autowired
    private AttributeTypeRepository attributeTypeRepository;

    @Autowired
    private AttributeValueRepository attributeValueRepository;

    @Autowired
    private WorkshopRepository workshopRepository;

    @Autowired
    private AccessGrantRepository accessGrantRepository;

    /**
     * C4.6 волна B: {@code Nomenclature} — первый aggregate root, который проходит
     * canonical fallback. Здесь проверяется сама предпосылка: типизированного сервиса нет,
     * {@code ServiceLocator} отдаёт canonical handle, а aggregate с owned-секцией
     * сохраняется тем же boundary, что и раньше, — header и строки в одной транзакции.
     */
    @Test
    void aggregateRootSavesThroughCanonicalHandleWithoutTypedService() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            assertThat(serviceLocator.findService(Nomenclature.class))
                .as("у Nomenclature нет application service — только canonical handle")
                .isInstanceOf(CanonicalEntityService.class);

            AttributeType type = stringType("AT-CANON-" + suffix, "Тип " + suffix);
            Nomenclature nomenclature = nomenclature(suffix);

            MetadataDrivenAggregateSaveService.AggregateSaveResult<Nomenclature> result =
                aggregateSaveService.save(nomenclature,
                    List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                        NomAttributeValue.class,
                        List.of(row(type, "значение-" + suffix)))),
                    EventSource.UI);

            assertThat(result.aggregate().getId()).isNotNull();
            List<NomAttributeValue> persisted = rowsOf(result.aggregate());
            assertThat(persisted)
                .as("owned-строка сохранена aggregate boundary вместе с шапкой")
                .hasSize(1);
            assertThat(persisted.get(0).getAttrType().getId()).isEqualTo(type.getId());
            assertThat(persisted.get(0).getAttrValue().getCode()).isEqualTo("значение-" + suffix);
        });
    }

    @Test
    void sectionIsDeclaredAsOwnedMutableSectionOfNomenclature() {
        TableSectionMetadataInfo descriptor = sectionRegistry.findByRow(NomAttributeValue.class)
            .orElseThrow();
        assertThat(descriptor.getOwnerClass()).isEqualTo(Nomenclature.class);
        assertThat(sectionRegistry.forOwner(Nomenclature.class))
            .extracting(TableSectionMetadataInfo::getRowClass)
            .contains(NomAttributeValue.class);
    }

    @Test
    void savesHeaderAndResolvesTypedAttributeValuesInOneTransaction() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            String suffix = suffix();
            Nomenclature nomenclature = nomenclature(suffix);
            AttributeType color = stringType("COLOR-" + suffix, "Цвет " + suffix);
            AttributeType weight = numberType("WEIGHT-" + suffix, "Вес " + suffix);

            NomAttributeValue colorRow = new NomAttributeValue();
            colorRow.setAttrType(color);
            colorRow.setEnteredValue("  Красный  ");

            NomAttributeValue weightRow = new NomAttributeValue();
            weightRow.setAttrType(weight);
            weightRow.setEnteredValue("1,50");

            MetadataDrivenAggregateSaveService.AggregateSaveResult<Nomenclature> result =
                aggregateSaveService.save(nomenclature,
                    List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                        NomAttributeValue.class, List.of(colorRow, weightRow))),
                    EventSource.UI);

            assertThat(result.aggregate().getId()).isNotNull();
            List<NomAttributeValue> persisted = rowsOf(result.aggregate());
            assertThat(persisted).hasSize(2);
            assertThat(persisted).allSatisfy(row ->
                assertThat(row.getNomenclature().getId()).isEqualTo(result.aggregate().getId()));
            assertThat(persisted)
                .extracting(row -> row.getAttrValue().getCode())
                .containsExactlyInAnyOrder("Красный", "1.5");
            assertThat(persisted)
                .extracting(row -> row.getAttrType().getId())
                .containsExactlyInAnyOrder(color.getId(), weight.getId());
        });
    }

    @Test
    void resolvesReferenceValueThroughTargetDictionary() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            String suffix = suffix();
            Workshop workshop = workshopRepository.save(
                new Workshop("W-" + suffix, "Цех " + suffix));
            AttributeType workshopType = refType(
                "SHOP-" + suffix, "Цех " + suffix, Workshop.class.getName());

            NomAttributeValue row = new NomAttributeValue();
            row.setAttrType(workshopType);
            row.setEnteredRefId(workshop.getId());

            MetadataDrivenAggregateSaveService.AggregateSaveResult<Nomenclature> result =
                aggregateSaveService.save(nomenclature(suffix),
                    List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                        NomAttributeValue.class, List.of(row))),
                    EventSource.UI);

            assertThat(rowsOf(result.aggregate())).singleElement().satisfies(saved -> {
                AttributeValue value = saved.getAttrValue();
                assertThat(value.getRefId()).isEqualTo(workshop.getId());
                assertThat(value.getName()).isEqualTo(workshop.getDisplayName());
            });
        });
    }

    @Test
    void attachedEmptySectionClearsValuesAndAbsentSectionKeepsThem() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            String suffix = suffix();
            AttributeType color = stringType("COLOR-" + suffix, "Цвет " + suffix);

            NomAttributeValue row = new NomAttributeValue();
            row.setAttrType(color);
            row.setEnteredValue("Синий");

            MetadataDrivenAggregateSaveService.AggregateSaveResult<Nomenclature> created =
                aggregateSaveService.save(nomenclature(suffix),
                    List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                        NomAttributeValue.class, List.of(row))),
                    EventSource.UI);
            assertThat(rowsOf(created.aggregate())).hasSize(1);

            // ABSENT: секция не подключена — существующие значения не изменяются
            MetadataDrivenAggregateSaveService.AggregateSaveResult<Nomenclature> absent =
                aggregateSaveService.save(created.aggregate(), List.of(), EventSource.UI);
            assertThat(rowsOf(absent.aggregate())).hasSize(1);

            // ATTACHED + empty: осознанная очистка секции.
            // Сохранять надо версию из результата предыдущего шага: merge не обновляет
            // версию переданного detached-инстанса, и повторный save старого aggregate
            // — это конфликт оптимистичной блокировки сам с собой.
            MetadataDrivenAggregateSaveService.AggregateSaveResult<Nomenclature> cleared =
                aggregateSaveService.save(absent.aggregate(),
                    List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                        NomAttributeValue.class, List.of())),
                    EventSource.UI);
            assertThat(rowsOf(cleared.aggregate())).isEmpty();
        });
    }

    @Test
    void replacesRowForTheSameAttributeTypeWithinOneSave() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            String suffix = suffix();
            AttributeType color = stringType("COLOR-" + suffix, "Цвет " + suffix);

            NomAttributeValue row = new NomAttributeValue();
            row.setAttrType(color);
            row.setEnteredValue("Красный");

            MetadataDrivenAggregateSaveService.AggregateSaveResult<Nomenclature> created =
                aggregateSaveService.save(nomenclature(suffix),
                    List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                        NomAttributeValue.class, List.of(row))),
                    EventSource.UI);

            // «Удалили строку и завели заново тот же атрибут» в одном сохранении:
            // уникальный ключ (номенклатура, тип) не должен ломать replace-all
            NomAttributeValue replacement = new NomAttributeValue();
            replacement.setAttrType(color);
            replacement.setEnteredValue("Зелёный");

            MetadataDrivenAggregateSaveService.AggregateSaveResult<Nomenclature> updated =
                aggregateSaveService.save(created.aggregate(),
                    List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                        NomAttributeValue.class, List.of(replacement))),
                    EventSource.UI);

            assertThat(rowsOf(updated.aggregate())).singleElement().satisfies(saved ->
                assertThat(saved.getAttrValue().getCode()).isEqualTo("Зелёный"));
        });
    }

    @Test
    void rejectsDuplicateAttributeRows() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            String suffix = suffix();
            AttributeType color = stringType("COLOR-" + suffix, "Цвет " + suffix);

            NomAttributeValue first = row(color, "Красный");
            NomAttributeValue second = row(color, "Синий");

            assertThatThrownBy(() -> aggregateSaveService.save(nomenclature(suffix),
                List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                    NomAttributeValue.class, List.of(first, second))),
                EventSource.UI))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Строка 2")
                .hasMessageContaining("уже указан в строке 1");
        });
    }

    @Test
    void rejectsValueOfAnotherTypeAndInactiveType() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            String suffix = suffix();
            AttributeType color = stringType("COLOR-" + suffix, "Цвет " + suffix);
            AttributeType size = stringType("SIZE-" + suffix, "Размер " + suffix);

            AttributeValue valueOfSize = attributeValueRepository.save(
                new AttributeValue(size, "XL", "XL", "XL", null));
            NomAttributeValue foreign = new NomAttributeValue();
            foreign.setAttrType(color);
            foreign.setAttrValue(valueOfSize);

            assertThatThrownBy(() -> aggregateSaveService.save(nomenclature(suffix),
                List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                    NomAttributeValue.class, List.of(foreign))),
                EventSource.UI))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("не принадлежит типу");

            AttributeType inactive = stringType("OLD-" + suffix, "Старый " + suffix);
            inactive.setActive(false);
            attributeTypeRepository.save(inactive);
            NomAttributeValue inactiveRow = row(inactive, "значение");

            assertThatThrownBy(() -> aggregateSaveService.save(nomenclature(suffix),
                List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                    NomAttributeValue.class, List.of(inactiveRow))),
                EventSource.UI))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("неактивен");
        });
    }

    @Test
    void rejectsRowWithoutValue() {
        RlsTestFixture.runAsSuperuser(accessGrantRepository, () -> {
            String suffix = suffix();
            AttributeType color = stringType("COLOR-" + suffix, "Цвет " + suffix);
            NomAttributeValue empty = new NomAttributeValue();
            empty.setAttrType(color);

            assertThatThrownBy(() -> aggregateSaveService.save(nomenclature(suffix),
                List.of(MetadataDrivenAggregateSaveService.SectionInput.attached(
                    NomAttributeValue.class, List.of(empty))),
                EventSource.UI))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Не заполнено значение атрибута");
        });
    }

    // === helpers ===

    private List<NomAttributeValue> rowsOf(Nomenclature nomenclature) {
        TableSectionMetadataInfo descriptor = sectionRegistry.findByRow(NomAttributeValue.class)
            .orElseThrow();
        return sectionService.findByParent(nomenclature, descriptor);
    }

    private static NomAttributeValue row(AttributeType type, String value) {
        NomAttributeValue row = new NomAttributeValue();
        row.setAttrType(type);
        row.setEnteredValue(value);
        return row;
    }

    /**
     * Единица измерения обязательна для номенклатуры и имеет уникальный {@code code},
     * поэтому helper идемпотентен: один и тот же тест может запрашивать номенклатуру
     * несколько раз (например, чтобы проверить несколько правил подряд), и это не должно
     * упираться в уникальный индекс вместо проверяемого правила.
     */
    private Nomenclature nomenclature(String suffix) {
        String unitCode = "U" + suffix;
        // Fixture-lookup идёт через canonical-совместимый findAll, а не через удалённый
        // C4.8 repository-метод findByCode (единственным потребителем был этот helper).
        UnitOfMeasurement unit = unitRepository.findAll().stream()
            .filter(candidate -> unitCode.equals(candidate.getCode()))
            .findFirst()
            .orElseGet(() -> unitRepository.save(new UnitOfMeasurement(
                "u" + suffix, "Unit " + suffix, unitCode)));
        return new Nomenclature("N-" + suffix, "Nom " + suffix, unit);
    }

    private AttributeType stringType(String code, String name) {
        return attributeTypeRepository.save(
            new AttributeType(code, name, AttributeValueType.STRING));
    }

    private AttributeType numberType(String code, String name) {
        return attributeTypeRepository.save(
            new AttributeType(code, name, AttributeValueType.NUMBER));
    }

    private AttributeType refType(String code, String name, String targetDictionary) {
        AttributeType type = new AttributeType(code, name, AttributeValueType.REF);
        type.setTargetDictionary(targetDictionary);
        return attributeTypeRepository.save(type);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
