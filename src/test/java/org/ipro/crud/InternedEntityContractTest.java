package org.ipro.crud;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Table;
import org.ip.model.AttributeType;
import org.ip.model.AttributeValue;
import org.ip.model.AttributeValueType;
import org.ip.model.Nomenclature;
import org.ip.model.SklNomOpa;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт интернированного экземпляра (см. {@link InternedEntity}): идентичность обязана
 * быть закреплена схемой, а не соглашением в коде.
 *
 * <p>Это и есть граница между интернированным типом и справочником: у
 * {@link StandardCatalogEntity} код тоже выглядит натуральным ключом, но глобальная
 * uniqueness там намеренно не задаётся, потому что строки ведёт пользователь. Здесь
 * наоборот — дедупликация обязана быть гарантирована БД, иначе гонка двух создателей
 * даст дубль, который не поймает ни retry, ни канонизация.</p>
 */
class InternedEntityContractTest {

    private static final List<Class<?>> KNOWN_INTERNED =
        List.of(AttributeValue.class, SklNomOpa.class);

    @Test
    void everyInternedTypeDeclaresDatabaseEnforcedIdentity() {
        List<JavaClass> interned = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("org.ip.model")
            .stream()
            .filter(type -> !type.getName().equals(InternedEntity.class.getName()))
            .filter(type -> type.isAssignableTo(InternedEntity.class))
            .toList();

        assertThat(interned)
            .as("маркер интернирования потерял реализации — проверка стала бессмысленной")
            .isNotEmpty();

        for (JavaClass type : interned) {
            Class<?> reflected = type.reflect();
            Table table = reflected.getAnnotation(Table.class);

            assertThat(table)
                .as("%s объявлен интернированным, но без @Table(uniqueConstraints) его"
                    + " идентичность не закреплена схемой", type.getName())
                .isNotNull();
            assertThat(table.uniqueConstraints())
                .as("интернированному типу %s нужен уникальный индекс натурального ключа",
                    type.getName())
                .isNotEmpty();
            assertThat(reflected.isAnnotationPresent(MappedSuperclass.class))
                .as("интернирование — поведенческий маркер, а не общий набор колонок: %s",
                    type.getName())
                .isFalse();
        }
    }

    @Test
    void knownInternedTypesAreCoveredByTheFence() {
        assertThat(KNOWN_INTERNED)
            .allSatisfy(type -> assertThat(InternedEntity.class).isAssignableFrom(type));
    }

    /**
     * Ключ интернирования — единственное определение идентичности: он стабилен для равного
     * содержимого и различает разные экземпляры, поэтому им можно пользоваться и в
     * диагностике гонки, и в проверках канонизации.
     */
    @Test
    void attributeValueKeyIsStableAndIdentitySpecific() {
        AttributeType type = new AttributeType("COLOR", "Цвет", AttributeValueType.STRING);
        String scalar = AttributeValue.interningKeyOf(type, "КРАСНЫЙ", null);

        assertThat(scalar)
            .isNotBlank()
            .isEqualTo(AttributeValue.interningKeyOf(type, "КРАСНЫЙ", null))
            .isNotEqualTo(AttributeValue.interningKeyOf(type, "СИНИЙ", null))
            .isNotEqualTo(AttributeValue.interningKeyOf(type, null, 42L));

        AttributeValue row = new AttributeValue(type, "Красный", "Красный", "КРАСНЫЙ", null);
        assertThat(row.interningKey()).isEqualTo(scalar);

        assertThat(AttributeValue.interningKeyOf(type, null, 42L))
            .isEqualTo(AttributeValue.interningKeyOf(type, null, 42L))
            .isNotEqualTo(AttributeValue.interningKeyOf(type, null, 43L));
    }

    @Test
    void sklNomOpaKeyIsStableAndCanonicalSpecific() {
        Nomenclature nomenclature = new Nomenclature();

        String canonical = SklNomOpa.interningKeyOf(nomenclature, "1:10;2:20");

        assertThat(canonical)
            .isNotBlank()
            .isEqualTo(SklNomOpa.interningKeyOf(nomenclature, "1:10;2:20"))
            .isNotEqualTo(SklNomOpa.interningKeyOf(nomenclature, "1:10;2:21"))
            // пустой набор — отдельный экземпляр (аналог нулевого кортежа), не «нет набора»
            .isNotEqualTo(SklNomOpa.interningKeyOf(nomenclature, ""));

        SklNomOpa emptySet = new SklNomOpa(nomenclature, "", "");
        assertThat(emptySet.interningKey()).isEqualTo(SklNomOpa.interningKeyOf(nomenclature, ""));
    }
}
