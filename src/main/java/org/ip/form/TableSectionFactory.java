package org.ip.form;

import org.ip.form.builtin.ItemForm;
import org.ip.form.builtin.ItemTable;
import org.ip.form.registry.FormResolver;
import org.ip.form.registry.FormRegistry;
import org.ip.form.registry.FormType;
import org.ip.metadata.MetadataResolver;
import org.ip.metadata.TableSectionMetadataInfo;
import org.ip.service.TableSectionService;
import org.ipro.crud.IdentifiableEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

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
    private final ObjectProvider<FormResolver> formResolverProvider;
    private final List<TableSectionCustomization<?>> customizations;

    public TableSectionFactory(MetadataResolver metadataResolver,
                               FieldFactory fieldFactory,
                               ApplicationContext applicationContext,
                               ObjectProvider<FormResolver> formResolverProvider,
                               List<TableSectionCustomization<?>> customizations) {
        this.metadataResolver = metadataResolver;
        this.fieldFactory = fieldFactory;
        this.applicationContext = applicationContext;
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends IdentifiableEntity> ItemTable<?, T> createItemTable(TableSectionMetadataInfo section) {
        TableSectionService rawService = findTableSectionService(section);
        java.util.function.Supplier<org.ip.form.registry.FormResolver> formResolverSupplier =
            formResolverProvider::getObject;
        ItemTable table = new ItemTable(section, fieldFactory, rawService, metadataResolver,
            applicationContext.getBean(org.ip.service.GridFormViewService.class),
            applicationContext.getBean(org.ip.service.FormSettingsService.class),
            applicationContext.getBean(org.ip.service.LookupService.class),
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
     * Находит Spring-бин TableSectionService для табличной части.
     *
     * Стратегия аналогична FormCoordinator.findService() для BaseService:
     *   1. @TableSectionMetadata.serviceClass(), если указан
     *   2. Fallback: бин по имени "<rowClassName>Service"
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

        String serviceName = uncapitalize(section.getRowClass().getSimpleName()) + "Service";
        try {
            return (TableSectionService<?, ?>) applicationContext.getBean(serviceName);
        } catch (Exception e) {
            throw new IllegalStateException(
                "No TableSectionService found for " + section.getRowClass().getSimpleName() + ". " +
                "Expected bean name: '" + serviceName + "'. " +
                "Solutions:\n" +
                "  1. Add serviceClass to @TableSectionMetadata: serviceClass = YourItemService.class\n" +
                "  2. Create a @Service class named " + capitalize(serviceName) + "\n" +
                "  3. Rename your service bean to '" + serviceName + "'", e);
        }
    }

    private String uncapitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}
