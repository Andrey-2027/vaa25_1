package org.ipro.vaadin.explorer;

import org.ipro.crud.BaseEntity;
import org.ipro.data.EntityCapabilities;
import org.ipro.data.EntityDescriptor;
import org.ipro.data.EntityExposure;
import org.ipro.form.registry.FormRegistry;
import org.ipro.metadata.*;
import org.ipro.metadata.annotation.*;
import org.ipro.metadata.facet.*;
import org.ipro.numbering.NumberingMetadataRegistry;
import java.util.*;

/** Explicit, bounded UI fixture. No application models, Spring context, database or entity scan. */
public final class RegistryFixture {
    public static class Named extends BaseEntity {
        @FieldMetadata(label="Код", order=1) String code;
        @FieldMetadata(label="Наименование", order=2) String name;
    }
    @EntityMetadata(kind=EntityKind.CATALOG, listFormTitle="Группы номенклатуры") public static class GroupNom extends Named {}
    @EntityMetadata(kind=EntityKind.CATALOG, listFormTitle="Номенклатура") public static class Nomenclature extends Named {
        @FieldMetadata(label="Единица измерения", order=3) String unit;
        @FieldMetadata(label="Группа номенклатуры", order=4) String group;
        @FieldMetadata(label="Артикул", order=5) String article;
        @FieldMetadata(label="Комментарий", order=6) String note;
    }
    @EntityMetadata(kind=EntityKind.CATALOG, listFormTitle="Единицы измерения") public static class UnitOfMeasurement extends Named {}
    @EntityMetadata(kind=EntityKind.CATALOG, listFormTitle="Цеха") public static class Workshop extends Named {}
    @EntityMetadata(kind=EntityKind.DOCUMENT, listFormTitle="Приёмно-сдаточные накладные") public static class ReceivingDocument extends Named {}
    @EntityMetadata(kind=EntityKind.DOCUMENT, listFormTitle="Установки объектов учёта") public static class AccountingObjectInstallationWithAnExceptionallyLongClassName extends Named {}
    @EntityMetadata(kind=EntityKind.INFORMATION_REGISTER, listFormTitle="Цены номенклатуры") public static class NomenclaturePrice extends Named {}
    @EntityMetadata(kind=EntityKind.PLAIN, listFormTitle="Наборы атрибутов КСУ") public static class SklNomOpa extends Named {}
    @EntityMetadata(kind=EntityKind.PLAIN, listFormTitle="Без подсистемы") public static class NoSubsystem extends Named {}
    @EntityMetadata(kind=EntityKind.CATALOG, listFormTitle="Служебное хранилище") public static class Service extends Named {}
    @EntityMetadata(listFormTitle="Неизвестная классификация") public static class Unknown extends Named {}
    @EntityMetadata(kind=EntityKind.CATALOG, listFormTitle="Сбой сборки метаданных") public static class Broken extends Named {}
    @EntityMetadata(kind=EntityKind.CATALOG, listFormTitle="Owned-строка") public static class Owned extends Named {}
    public static final List<Class<?>> TYPES = List.of(GroupNom.class,Nomenclature.class,UnitOfMeasurement.class,Workshop.class,
        ReceivingDocument.class,AccountingObjectInstallationWithAnExceptionallyLongClassName.class,NomenclaturePrice.class,
        SklNomOpa.class,NoSubsystem.class,Service.class,Unknown.class,Broken.class,Owned.class);
    public static final class Directories {}
    public static final class Production {}

    private RegistryFixture() {}
    public static ExplorerSnapshot snapshot() { return snapshot(true); }
    public static ExplorerSnapshot snapshot(boolean diagnosticsKnown) {
        var metadata = new MetadataResolver();
        var types = new ManagedEntityTypes(TYPES);
        var sections = new SectionMetadataRegistry(types, metadata); sections.rebuild();
        var descriptors = new org.ipro.data.EntityDescriptorCatalog(new ManagedEntityCatalog(types), sections, metadata,
            List.of(new org.ipro.data.EntityExposureOverride(Service.class,EntityExposure.INTERNAL_STORE,null,"UI fixture"),
                new org.ipro.data.EntityExposureOverride(Owned.class,EntityExposure.OWNED_ROW,ReceivingDocument.class,"UI fixture")), List.of(),
            List.of(type -> type == NomenclaturePrice.class ? Optional.of(new org.ipro.data.EntityClassificationContributor.Contribution(
                EntityKind.INFORMATION_REGISTER,FactOrigin.REGISTRATION,"explicit-ui-fixture",false)) : Optional.empty()));
        var assembler = new EntitySummaryAssembler("unused", metadata, new FormRegistry(),
            new ReferenceIndex(types), new NumberingMetadataRegistry(types),
            new SubsystemRegistry("unused", types, metadata), FacetResolver.none(), descriptors,null,null,sections,null);
        var inventory = TYPES.stream().map(type -> new EntitySummaryAssembler.EntityRef(type, type.getSimpleName(),
            ResolvedValue.fact(metadata.resolve(type).getListFormTitle(), FactOrigin.EXPLICIT, type.getName()))).toList();
        return ExplorerSnapshot.build(new ExplorerSnapshot.Sources(inventory,
            type -> type == Unknown.class ? null : metadata.resolve(type).getEntityKind(),
            type -> type == Unknown.class ? null : descriptors.descriptorOf(type),
            type -> type == NoSubsystem.class || type == Unknown.class ? Optional.empty() : Optional.of(new ExplorerSnapshot.SubsystemRef(
                (type == NomenclaturePrice.class ? Production.class : Directories.class).getName(), ResolvedValue.fact(
                    type == NomenclaturePrice.class ? "Производство" : "Справочники", FactOrigin.EXPLICIT, "fixture"))),
            type -> type == ReceivingDocument.class ? List.of(new ExplorerSnapshot.OwnedSection(type,type.getName(),"owner",Owned.class,
                Owned.class.getName(),"Owned",ResolvedValue.fact("Строки накладной",FactOrigin.EXPLICIT,"fixture"),1)) : List.of(),
            type -> type == Service.class || type == Unknown.class ? Optional.empty() : Optional.of(type.getSimpleName().replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT)),
            type -> {
                if (type == Broken.class) throw new IllegalStateException("Fixture: источник метаданных недоступен");
                var value = assembler.summarize(type);
                if (type != GroupNom.class && type != AccountingObjectInstallationWithAnExceptionallyLongClassName.class) return value;
                return new EntitySummary(value.entityClass(),value.simpleName(),value.displayName(),value.overview(),value.fieldsForm(),value.fieldsGrid(),
                    value.listColumns(),value.selectColumns(),value.tableSections(),value.forms(),value.contextFilters(),value.selections(),value.references(),
                    value.numbering(),value.lifecycle(),List.of(message("FIXTURE_WARNING", "Проверьте подпись формы")),value.actions(),value.readPlans(),value.accessRows(),
                    value.lookupTargets(),value.readPlanInspectionAvailable(),value.structure());
            }, diagnosticsKnown, () -> List.of(message("FIXTURE_COMMON", "Пример общего сообщения каталога"))));
    }
    private static EntitySummary.DiagnosticRow message(String code, String text) {
        return new EntitySummary.DiagnosticRow(MetadataDiagnostic.Severity.WARNING,code,"","",text,null,
            ResolvedValue.fact(text, FactOrigin.DERIVED,"fixture"),"");
    }
}
