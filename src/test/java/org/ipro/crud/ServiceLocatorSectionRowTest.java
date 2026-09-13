package org.ipro.crud;

import org.ip.model.NomAttributeValue;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.SectionMetadataRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * У строки owned-секции нет автономного сервиса/списка: её сохранение принадлежит
 * aggregate boundary владельца, а чтение отдельным repository не может выразить
 * обязательный предикат владельца.
 *
 * <p>Проверяется именно диагностика: обычная ветка {@link ServiceLocator} на
 * ненайденный бин советует «создать service», и этот совет для строки секции — прямо
 * неверный. Отказ обязан приходить до поиска бина и называть настоящую причину.</p>
 */
class ServiceLocatorSectionRowTest {

    @Test
    void sectionRowIsRejectedWithTheRealReasonInsteadOfMissingServiceAdvice() {
        MetadataResolver resolver = new MetadataResolver();
        SectionMetadataRegistry sections = new SectionMetadataRegistry("org.ip", resolver);
        sections.afterPropertiesSet();
        ServiceLocator locator =
            new ServiceLocator(mock(ApplicationContext.class), resolver, sections);

        assertThatThrownBy(() -> locator.findService(NomAttributeValue.class))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("owned-секции")
            .hasMessageContaining("Nomenclature.NomAttributeValue")
            .hasMessageContaining("GenericOwnedSectionService");
    }
}
