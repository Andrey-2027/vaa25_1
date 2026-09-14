package org.ipro.metadata;

import org.ip.model.NomAttributeValue;
import org.ip.model.Nomenclature;
import org.ipro.metadata.annotation.EntityMetadata;
import org.ipro.metadata.annotation.Subsystem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Инвариант колонок строк табличных частей: грид секции показывает только собственные
 * колонки строки, а связь с родителем остаётся metadata-полем.
 *
 * <p>Родитель не просто не размечен {@code @GridColumn}: у {@code @GridColumn.visible()}
 * дефолт {@code true}, поэтому поле с любым {@code @FieldMetadata} без явного
 * {@code visible = false} всё равно попадает в грид. Тест ловит эту ловушку — иначе
 * «убрать колонку» удалением атрибута {@code grid} тихо оставляет её последней колонкой.</p>
 */
class SectionParentColumnIsolationTest {

    private final MetadataResolver resolver = new MetadataResolver();

    @Test
    void everySectionRowGridOmitsTheParentLinkageColumn() {
        SectionMetadataRegistry registry = new SectionMetadataRegistry("org.ip", resolver);
        registry.afterPropertiesSet();

        assertThat(registry.all()).isNotEmpty();
        for (TableSectionMetadataInfo section : registry.all()) {
            assertThat(section.getGridFields())
                .as("секция %s: связь с родителем не должна дублировать шапку колонкой",
                    section.getKey())
                .extracting(FieldMetadataInfo::getName)
                .doesNotContain(section.getParentFieldName());
        }
    }

    @Test
    void nomenclatureAttributesGridHasOnlyRowOwnColumns() {
        TableSectionMetadataInfo section = resolver.resolveTableSections(Nomenclature.class).stream()
            .filter(row -> row.getRowClass() == NomAttributeValue.class)
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Секция NomAttributeValue не объявлена у Nomenclature"));

        assertThat(section.getGridFields())
            .extracting(FieldMetadataInfo::getName)
            .containsExactly("attrType", "attrValue");

        EntityMetadataInfo row = resolver.resolve(NomAttributeValue.class);
        assertThat(row.getFieldByName("nomenclature").isGridVisible()).isFalse();
    }

    /**
     * Инвариант платформенного уровня: строка owned-секции не объявляет себя
     * самостоятельным справочником — ни узлом подсистемы, ни автономным сервисом.
     *
     * <p>Это не косметика. Строка не имеет собственной RLS-политики — доступ наследуется
     * от агрегата, — поэтому автономный список читал бы её repository в обход обязательного
     * предиката владельца. Такой repository аспект закрывает, значит список упал бы уже
     * у пользователя; запрет на уровне metadata переводит дефект в отказ при старте.</p>
     */
    @Test
    void everySectionRowIsNotAnAutonomousCatalog() {
        SectionMetadataRegistry registry = new SectionMetadataRegistry("org.ip", resolver);
        registry.afterPropertiesSet();

        for (TableSectionMetadataInfo section : registry.all()) {
            // Строка вообще может не иметь @EntityMetadata (так объявлены PrdSpecMtr,
            // PrdSpecOper и ReceivingDocumentItem) — тогда автономных форм у неё нет
            // структурно, и проверять нечего.
            EntityMetadata rowMetadata =
                section.getRowClass().getAnnotation(EntityMetadata.class);
            if (rowMetadata == null) {
                continue;
            }

            assertThat(rowMetadata.subsystem())
                .as("строка %s не должна создавать узел подсистемы", section.getKey())
                .isEqualTo(Subsystem.NoSubsystem.class);
            // C4.7: атрибут serviceClass удалён вместе с compatibility base. Автономность
            // строки выражается структурно — она не metadata-driven root, поэтому у неё нет
            // ни своего узла подсистемы, ни типизированной регистрации в ServiceLocator;
            // попытка получить для неё data handle падает в самом ServiceLocator.
        }
    }
}
