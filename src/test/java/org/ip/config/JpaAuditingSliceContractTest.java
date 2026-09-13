package org.ip.config;

import org.ip.model.AttributeType;
import org.ip.model.AttributeValueType;
import org.ip.repository.AttributeTypeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Аудит — часть границы маппинга {@link org.ipro.crud.BaseEntity}: его инфраструктура
 * обязана быть в КАЖДОМ контексте, который маппит эту сущность, включая slice-контексты
 * тестов.
 *
 * <p>Контракт, который здесь закреплён, — не «поля заполнились», а «контекст не берёт
 * аудит взаймы». {@code AuditingEntityListener} стоит на {@code BaseEntity} безусловно и
 * настраивается JVM-глобальным AspectJ-аспектом {@code AnnotationBeanConfigurerAspect};
 * если slice-контекст своей инфраструктуры аудита не имеет, аспект отдаёт ему
 * {@code ObjectFactory} чужого контекста. Пока тот жив — аудит пишется «по доверенности»,
 * когда закрывается — запись падает с
 * {@code IllegalStateException: … has been closed already}. Без этих проверок дефект
 * выглядел как порядко-зависимость набора тестов и не воспроизводился в изоляции.</p>
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JpaAuditingSliceContractTest {

    @Autowired
    private AttributeTypeRepository attributeTypeRepository;

    @Autowired
    private ApplicationContext context;

    @Test
    void sliceContextWiresItsOwnAuditingInfrastructure() {
        assertThat(context.containsBean("jpaAuditingHandler"))
            .as("без своего jpaAuditingHandler контекст берёт аудит из чужого контекста")
            .isTrue();
        assertThat(context.containsBean(
            "org.springframework.context.config.internalBeanConfigurerAspect"))
            .as("без регистрации аспекта в этом контексте его beanFactory остаётся чужим")
            .isTrue();
        assertThat(context.getBeansOfType(org.springframework.data.domain.AuditorAware.class))
            .as("аудитор — часть той же инфраструктуры, а не отдельная настройка")
            .isNotEmpty();
    }

    @Test
    void savedEntityGetsFullAuditOnCreateAndUpdate() {
        AttributeType type = attributeTypeRepository.save(
            new AttributeType("AUD-1", "Аудит", AttributeValueType.STRING));

        assertThat(type.getCreatedAt()).isNotNull();
        assertThat(type.getCreatedBy()).isEqualTo("system");
        assertThat(type.getModifiedAt()).isNotNull();
        assertThat(type.getModifiedBy()).isEqualTo("system");

        type.setName("Аудит-2");
        AttributeType updated = attributeTypeRepository.save(type);

        assertThat(updated.getCreatedBy()).isEqualTo("system");
        assertThat(updated.getModifiedBy()).isEqualTo("system");
    }
}
