package org.ipro.form;

import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ItemTable;
import org.ipro.form.registry.FormResolver;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormType;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.ipro.crud.GenericOwnedSectionService;
import org.ipro.crud.MetadataTableSectionService;
import org.ipro.crud.TableSectionService;
import org.ipro.crud.IdentifiableEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Строит ItemTable для табличных частей сущности и подключает их к ItemForm.
 *
 * Единая точка, вызываемая из двух мест, которые создают generic ItemForm
 * (FormResolver — для Dialog-режима, ItemFormWrapperView — для Workspace-режима),
 * чтобы табличные части подключались одинаково независимо от режима открытия формы.
 *
 * Валидация объявленных вариантов строк (PR-1.4) выполняется в {@link #run} как
 * ApplicationRunner — после полного старта контекста, когда FormRegistry уже наполнен
 * ItemFormCustomizationRegistrar'ом (в lifecycle-фазе бина была бы круговая зависимость:
 * FormResolver → TableSectionFactory → FormResolver).
 */
@Component
public class TableSectionFactory implements ApplicationRunner {

    private final MetadataResolver metadataResolver;
    private final FieldFactory fieldFactory;
    private final ApplicationContext applicationContext;
    private final GenericOwnedSectionService genericSectionService;
    private final ObjectProvider<FormResolver> formResolverProvider;
    private final List<TableSectionCustomization<?>> customizations;

    public TableSectionFactory(MetadataResolver metadataResolver,
                               FieldFactory fieldFactory,
                               ApplicationContext applicationContext,
                               GenericOwnedSectionService genericSectionService,
                               ObjectProvider<FormResolver> formResolverProvider,
                               List<TableSectionCustomization<?>> customizations) {
        this.metadataResolver = metadataResolver;
        this.fieldFactory = fieldFactory;
        this.applicationContext = applicationContext;
        this.genericSectionService = genericSectionService;
        this.formResolverProvider = formResolverProvider;
        this.customizations = customizations;
    }

    /**
     * Startup validation (PR-1.4 «strict variants»): если TableSectionCustomization объявляет
     * варианты формы строки ({@link TableSectionCustomization#declaredRowVariants()}), каждый
     * из них обязан быть зарегистрирован как ITEM-вариант формы строки в FormRegistry —
     * иначе конфигурация разъехалась (selector вернёт ключ, resolver упадёт по strict-политике
     * только при первом открытии формы строки) и старт должен упасть сразу.
     *
     * Выполняется после полного старта контекста: к этому моменту ItemFormCustomizationRegistrar
     * уже наполнил FormRegistry, а FormResolver полностью создан (без круговой зависимости).
     */
    @Override
    public void run(ApplicationArguments args) {
        FormRegistry registry = formResolverProvider.getObject().getFormRegistry();
        for (TableSectionCustomization<?> customization : customizations) {
            for (String variant : customization.declaredRowVariants()) {
                if (!registry.has(customization.rowClass(), FormType.ITEM, variant)) {
                    throw new IllegalStateException(
                        "Row variant '" + variant + "' declared by " +
                        customization.getClass().getSimpleName() + " for row class " +
                        customization.rowClass().getName() +
                        " is not registered as an ITEM form variant — check " +
                        "ItemFormCustomization.configure(ItemFormVariants).");
                }
            }
        }
    }

    /**
     * Резолвит табличные части для entityClass и подключает их к форме —
     * состав и режим секций берутся из самой формы ({@link ItemForm#getSectionFilter()},
     * {@link ItemForm#isSectionReadOnly(Class)}), куда их кладёт фабрика варианта или
     * точка открытия (PR-1.5, решение №7).
     * Если табличных частей нет — ничего не делает (form остаётся как есть).
     */
    public <T extends IdentifiableEntity> void attachTableSections(ItemForm<T> form, Class<T> entityClass) {
        attachTableSections(form, entityClass, form.getSectionFilter());
    }

    /**
     * Резолвит табличные части для entityClass и подключает к форме только те, чей
     * row-класс есть в {@code rowClasses} (скрытая секция не attach-ится — не участвует
     * в save/validate/rows и не создаёт вкладку).
     *
     * <p>Секции, помеченные read-only через {@code form.setReadOnlySections(...)}, переводятся
     * в режим «только просмотр» сразу при attach — кнопки Добавить/Изменить/Удалить для них
     * не появляются, шапка и остальные секции остаются редактируемыми.</p>
     *
     * @param rowClasses row-классы секций к подключению; null = все секции
     */
    public <T extends IdentifiableEntity> void attachTableSections(ItemForm<T> form, Class<T> entityClass,
                                                                   Collection<Class<?>> rowClasses) {
        List<TableSectionMetadataInfo> sections = metadataResolver.resolveTableSections(entityClass);
        for (TableSectionMetadataInfo section : sections) {
            if (rowClasses != null && !rowClasses.contains(section.getRowClass())) {
                continue;
            }
            ItemTable<?, T> table = createItemTable(section);
            if (form.isSectionReadOnly(section.getRowClass())) {
                table.setReadOnly(true);
            }
            form.addTableSection(section.getTitle(), table);
        }
    }

    /**
     * Копирует все табличные части родителя для команды «Копировать» (1С-стиль: целиком).
     *
     * @return карта класс строки → скопированные строки (без пустых секций);
     *         строки перепривязаны на {@code newParent} (может быть не сохранён),
     *         номера строк — с 1 по порядку исходных
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Map<Class<?>, List<?>> copyRowsFor(Class<?> parentClass,
                                              IdentifiableEntity sourceParent,
                                              IdentifiableEntity newParent,
                                              org.ipro.crud.EntityCopyService copy) {
        Map<Class<?>, List<?>> result = new java.util.LinkedHashMap<>();
        for (TableSectionMetadataInfo section : metadataResolver.resolveTableSections(parentClass)) {
            TableSectionService rawService = findTableSectionService(section);
            List<?> sourceRows = rawService.findByParent(sourceParent);
            List<Object> copied = new java.util.ArrayList<>(sourceRows.size());
            int lineNumber = 1;
            for (Object row : sourceRows) {
                Object rowCopy = copy.copyRow(row, section, newParent);
                if (section.hasLineNumberField()) {
                    section.setLineNumber(rowCopy, lineNumber++);
                }
                copied.add(rowCopy);
            }
            if (!copied.isEmpty()) {
                result.put(section.getRowClass(), copied);
            }
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity> ItemTable<?, T> createItemTable(TableSectionMetadataInfo section) {
        TableSectionService rawService = findTableSectionService(section);
        java.util.function.Supplier<org.ipro.form.registry.FormResolver> formResolverSupplier =
            formResolverProvider::getObject;
        ItemTable table = new ItemTable(section, fieldFactory, rawService, metadataResolver,
            applicationContext.getBean(org.ipro.form.spi.GridViewStore.class),
            applicationContext.getBean(org.ipro.form.spi.FormSettingsStore.class),
            applicationContext.getBean(org.ipro.crud.LookupService.class),
            formResolverSupplier);
        for (TableSectionCustomization<?> customization : customizations) {
            if (customization.rowClass() == section.getRowClass()) {
                ((TableSectionCustomization) customization).configure(table);
                break;
            }
        }
        return table;
    }

    /**
     * Возвращает UI-service для табличной части.
     *
     * <p>Явный {@code serviceClass} остаётся escape hatch для нестандартного UI
     * поведения (таблица, fetch, копирование строк). Стандартная секция получает
     * descriptor-bound platform adapter и не требует application service/repository.
     * Поиск по magic bean name удалён.</p>
     *
     * <p>Контракт UI-only: authoritative validation и persistence стандартного
     * aggregate save всегда выполняет {@code GenericOwnedSectionService} через
     * {@code MetadataDrivenAggregateSaveService}; custom {@code serviceClass} в этом
     * пути не вызывается. Нестандартная persistence-семантика требует явного custom
     * aggregate handler.</p>
     */
    private TableSectionService<?, ?> findTableSectionService(TableSectionMetadataInfo section) {
        Class<?> serviceClass = section.getServiceClass();

        if (serviceClass != null && serviceClass != void.class) {
            try {
                return (TableSectionService<?, ?>) applicationContext.getBean(serviceClass);
            } catch (Exception e) {
                throw new IllegalStateException(
                    "Service class specified in @TableSectionMetadata not found: " +
                    serviceClass.getName() + ". Make sure " + serviceClass.getSimpleName() +
                    " is a Spring @Service bean.", e);
            }
        }

        return new MetadataTableSectionService<>(genericSectionService, section);
    }
}
