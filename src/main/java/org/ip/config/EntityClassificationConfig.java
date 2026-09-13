package org.ip.config;

import org.ip.model.AttributeValue;
import org.ip.model.GridFormView;
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
     * Виды грида — общий UI-стор: кроме прав RLS на update/delete действует ownership
     * (чужой/личный вид редактировать нельзя). Ownership-проверка живёт в
     * {@code GridFormViewService.checkEditable} и не может быть исполнена canonical
     * pipeline, поэтому canonical handle ограничен созданием: update/delete идут только
     * через типизированный сервис, а не через публичный {@code EntityDataAccess}.
     *
     * <p>CREATE безопасен на canonical path: новая строка ещё не принадлежит другому
     * автору, а создание собственником проверки не требует.</p>
     */
    @Bean
    public EntityCapabilityOverride gridFormViewCanonicalWritesAreCreateOnly() {
        return new EntityCapabilityOverride(GridFormView.class,
            Set.of(FetchScenario.LIST, FetchScenario.DETAIL, FetchScenario.LOOKUP),
            Set.of(DataOperation.CREATE),
            "UI-хранилище видов: canonical path допускает только создание; update/delete"
                + " ограничены ownership (GridFormViewService.checkEditable) и не"
                + " исполняются canonical pipeline");
    }
}
