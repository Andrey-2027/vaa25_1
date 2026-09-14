package org.ip.config;

import org.ip.model.AttributeValue;
import org.ip.model.SklNomOpa;
import org.ip.model.SklNomOpaValue;
import org.ipro.data.DataOperation;
import org.ipro.data.EntityCapabilityOverride;
import org.ipro.data.EntityExposure;
import org.ipro.data.EntityExposureOverride;
import org.ipro.fetch.plan.FetchScenario;
import org.ipro.ureport.dom.UreportTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

/**
 * Прикладные классификации persistence types, которые нельзя вывести из аннотаций
 * (C4, ADR-0007 §2 и находка {@code c4-inventory.md} §2.3).
 *
 * <p>{@link SklNomOpaValue} — структурная строка агрегата {@link SklNomOpa}: у неё нет
 * {@code @TableSectionMetadata}, потому что её состав ведёт typed {@code SklNomOpaService},
 * а не generic section machinery. Без явной классификации тип попал бы в
 * {@code INTERNAL_STORE}, и JPA-metamodel membership когда-нибудь выдал бы ему автономный
 * data handle. Явный override фиксирует {@code OWNED_ROW}: автономного list/detail/lookup
 * у строки нет.</p>
 *
 * <p>Остальные объявления ниже — {@link EntityCapabilityOverride}: экспозиция типа верна,
 * но generic canonical handle не совпадает с предметным правилом. Инвентарь фиксирует эти
 * ограничения в коде сервисов; здесь они становятся контрактом, который читает canonical
 * path, а не знанием внутри одного класса (ADR-0007 §2, «intentional prohibitions»).</p>
 *
 * <p>Объявлений здесь ровно столько, сколько запретов <b>нельзя</b> выразить исполняемым
 * правилом: после C4.6 волны F ownership-запрет {@code GridFormView} переехал в
 * {@code GridFormViewLifecycle} и перестал быть capability-сужением. То есть capability
 * override остаётся для «операции у типа нет вообще», а правило вида «операция есть, но не
 * для всех строк» выражается lifecycle handler'ом.</p>
 */
@Configuration(proxyBeanMethods = false)
public class EntityClassificationConfig {

    @Bean
    public EntityExposureOverride sklNomOpaValueIsAnOwnedRow() {
        return new EntityExposureOverride(SklNomOpaValue.class, EntityExposure.OWNED_ROW,
            SklNomOpa.class,
            "структурная строка агрегата SklNomOpa, состав ведёт typed use case");
    }

    /**
     * Каталог uReport — {@code INTERNAL_STORE} (нет {@code @EntityMetadata}, владелец —
     * подсистема отчётов), но его владелец читает шаблоны сервисом, который пока наследует
     * {@code AbstractBaseService}, то есть идёт через canonical path. Пока сервис не
     * переведён на owner-специфичный internal-store adapter (C4.3), владелец явно отдаёт
     * типу чтение списка и карточки. Lookup не выдан: ни один потребитель его не запрашивает,
     * и появление такого вызова должно падать, а не молча получать граф без metadata.
     */
    @Bean
    public EntityCapabilityOverride ureportTemplateOwnerReadBridge() {
        return new EntityCapabilityOverride(UreportTemplate.class,
            Set.of(FetchScenario.LIST, FetchScenario.DETAIL), Set.of(),
            "владелец подсистемы отчётов читает шаблоны через UreportTemplateService"
                + " (наследует AbstractBaseService); временный мост до перевода сервиса"
                + " на internal-store adapter");
    }

    /** Значение атрибута неизменяемо: generic update/delete запрещены typed policy. */
    @Bean
    public EntityCapabilityOverride attributeValueIsCreateOnly() {
        return new EntityCapabilityOverride(AttributeValue.class,
            Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP),
            Set.of(DataOperation.CREATE),
            "значение атрибута бессмертно (AttributeValueService); переименование —"
                + " отдельная typed операция, generic update/delete запрещены");
    }

    /**
     * Состав набора SklNomOpa создаётся канонизацией {@code findOrCreate}, а не generic
     * CRUD: собственные save/create/update/delete сервис запрещает намеренно.
     */
    @Bean
    public EntityCapabilityOverride sklNomOpaHasNoGenericWrites() {
        return new EntityCapabilityOverride(SklNomOpa.class,
            Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP),
            Set.of(),
            "набор immutable, состав ведёт канонизация findOrCreate; generic CRUD запрещён"
                + " typed use case");
    }

    /**
     * {@code GridFormView} намеренно <b>не</b> объявлен здесь. Раньше его canonical handle
     * был ограничен одним {@code CREATE}, потому что ownership-правило (чужой личный вид
     * менять нельзя) жило внутри {@code GridFormViewService} и canonical pipeline его не
     * исполняла. C4.6 волна F перенесла правило в
     * {@link org.ip.application.form.GridFormViewLifecycle}, поэтому сужение capabilities
     * снято: тип снова обычный {@code STANDARD_ROOT} с полным CRUD, а запрет исполняется
     * тем же write pipeline, что и остальные lifecycle-правила.
     *
     * <p>Оставшийся открытый вопрос ADR-0007 («{@code GridFormView} — {@code STANDARD_ROOT}
     * с custom policy или {@code INTERNAL_STORE}») решён в пользу первого варианта: тип
     * остаётся полноценной metadata-driven сущностью с собственным предметным доступом к
     * видам реестра, а не внутренним хранилищем.</p>
     */
}
