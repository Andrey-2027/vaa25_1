package org.ipro.vaadin.register;

import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.datetimepicker.DateTimePicker;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import org.ipro.crud.BaseEntity;
import org.ipro.crud.BaseService;
import org.ipro.data.EntityDataAccess;
import org.ipro.filtergrid.util.JpaPathUtil;
import org.ipro.filtergrid.filter.FilterFieldResolver;
import org.ipro.filtergrid.filter.FilterDataType;
import org.ipro.filtergrid.filter.FilterValueCodec;
import org.ipro.form.action.ActionDecision;
import org.ipro.form.action.ActionDefinition;
import org.ipro.form.action.ActionHandler;
import org.ipro.form.action.ActionId;
import org.ipro.form.action.ActionInvocation;
import org.ipro.form.action.ActionRequirement;
import org.ipro.form.action.ActionSurface;
import org.ipro.form.builder.FormTypeCustomization;
import org.ipro.form.builtin.ItemForm;
import org.ipro.form.builtin.ListForm;
import org.ipro.form.link.FormRouteOpener;
import org.ipro.form.registry.FormContext;
import org.ipro.identity.IdentifiableEntity;
import org.ipro.metadata.ColumnPath;
import org.ipro.register.RecordedRegisterEntry;
import org.ipro.register.RecorderRef;
import org.ipro.register.RegisterDescriptor;
import org.ipro.register.RegisterKeyChangePermissions;
import org.ipro.register.RegisterKeyChanges;
import org.ipro.register.RegisterListFilterPhases;
import org.ipro.register.RegisterPeriodicity;
import org.ipro.register.RegisterReaderFactory;
import org.ipro.register.RegisterRegistry;
import org.ipro.register.RegisterSliceReader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Один стандартный адаптер форм для каждого управляемого регистра сведений: режимы
 * «История/Срез последних», фазы отбора, блокировка ключа, команда смены ключа и колонки
 * регистратора.
 *
 * <p><b>D-R S4: адаптер уехал из приложения в модуль.</b> Класс был построен вокруг одного
 * вопроса — «что у регистра общего»; S1 убрал из него прикладную сборку читателя
 * ({@link RegisterReaderFactory} вместо {@code EntityManagerFactory}), поэтому к S4 общим
 * оказалось всё содержимое, а прикладных имён в нём не осталось ни одного. Переезд — перемещение,
 * а не переписывание: изменились адрес, способ регистрации (модульная авто-конфигурация вместо
 * component-scan приложения) и имя связанного резолвера представлений регистратора.</p>
 *
 * <p><b>Точки расширения — на месте.</b> Бин объявлен
 * {@code @ConditionalOnMissingBean}: потребитель, которому стандартный интерфейс не подходит
 * целиком, подменяет его своим. Частичная настройка — второй способ, и он уже есть у слоя форм:
 * {@code FormResolver} применяет <i>все</i> {@link FormTypeCustomization}, поддерживающие тип,
 * поэтому потребитель добавляет свой адаптер рядом, а не вместо. Обе точки поддерживаются
 * контейнером, а не договорённостью: первая — условием бина, вторая — композицией списка.</p>
 */
public class RegisterFormCustomization implements FormTypeCustomization {
    private final RegisterRegistry registers;
    private final EntityDataAccess access;
    private final RegisterReaderFactory readers;
    private final RegisterKeyChanges changes;
    private final RegisterKeyChangePermissions permissions;
    private final RecorderRefPresentationResolver captions;
    private final ObjectProvider<FormRouteOpener> routes;
    private final Clock clock;

    /**
     * S1: форма списка берёт срезовый читатель у той же фабрики, что и прикладной сервис.
     *
     * <p>До среза форма конструировала {@code RegisterSliceReader} сама из
     * {@code EntityManagerFactory}, игнорируя прикладной bean, — то есть у одного регистра было
     * две сборки читателя, и расхождение между формой и сервисом не было видно никому. Фабрика
     * здесь и передаётся вместо фабрики сущностей: форма не собирает читатель, а просит его по
     * типу регистра.</p>
     */
    public RegisterFormCustomization(RegisterRegistry registers, EntityDataAccess access,
            RegisterReaderFactory readers, RegisterKeyChanges changes, RegisterKeyChangePermissions permissions,
            RecorderRefPresentationResolver captions, ObjectProvider<FormRouteOpener> routes, ObjectProvider<Clock> clocks) {
        this.registers = registers; this.access = access; this.readers = readers;
        this.changes = changes; this.permissions = permissions; this.captions = captions; this.routes = routes;
        this.clock = clocks.getIfAvailable(Clock::systemDefaultZone);
    }
    @Override public boolean supports(Class<?> type) { return registers.find(type).isPresent(); }
    @Override public boolean supportsGrouping() { return false; }

    @Override public void configureListColumns(Class<?> type, org.ipro.metadata.FormColumnDeclarations.Columns columns) {
        registers.require(type).period().ifPresent(period -> columns.column(period.name()).setInlineFilter(false));
    }
    @Override public void configureSelectionColumns(Class<?> type, org.ipro.metadata.FormColumnDeclarations.Columns columns) {
        configureListColumns(type, columns);
    }

    @Override public void customizeItem(ItemForm<?> form, FormContext context) {
        RegisterDescriptor descriptor = registers.require(context.getEntityClass());
        form.addEntityStateListener(row -> {
            boolean persisted = row != null && row.getId() != null;
            List<String> key = new ArrayList<>(descriptor.dimensionNames());
            descriptor.period().ifPresent(field -> key.add(field.name()));
            for (String name : key) form.getBindingRegistry().getBinding(name).ifPresent(binding -> binding.setLocked(persisted));
        });
    }

    @Override @SuppressWarnings({"unchecked", "rawtypes"})
    public void customizeList(ListForm<?, ?> untyped, FormContext context) {
        configure((ListForm) untyped, context);
    }

    private <T extends IdentifiableEntity<Long>> void configure(ListForm<T, ?> form, FormContext context) {
        Class<T> type = (Class<T>) context.getEntityClass();
        BaseService<T, ?> service = (BaseService<T, ?>) context.service();
        RegisterDescriptor descriptor = registers.require(type);
        ComboBox<String> mode = new ComboBox<>();
        mode.setItems("История", "Срез последних");
        mode.setValue("История");
        mode.setWidth("210px");
        DateTimePicker asOf = new DateTimePicker();
        asOf.setStep(Duration.ofMillis(1));
        asOf.setValue(RegisterPeriodicity.EXACT.normalize(LocalDateTime.now(clock)));
        asOf.setWidthFull(); asOf.setMaxWidth("24em"); asOf.getStyle().set("min-width", "0");
        asOf.setRequiredIndicatorVisible(true);
        org.ipro.filtergrid.PeriodField<LocalDateTime> period = org.ipro.filtergrid.PeriodField.dateTimes(clock);
        period.setMaxWidth("32em");
        com.vaadin.flow.component.formlayout.FormLayout controls = new com.vaadin.flow.component.formlayout.FormLayout();
        controls.setWidthFull(); controls.setMaxWidth("42em");
        controls.setResponsiveSteps(new com.vaadin.flow.component.formlayout.FormLayout.ResponsiveStep("0", 1,
                com.vaadin.flow.component.formlayout.FormLayout.ResponsiveStep.LabelsPosition.TOP),
            new com.vaadin.flow.component.formlayout.FormLayout.ResponsiveStep("420px", 1,
                com.vaadin.flow.component.formlayout.FormLayout.ResponsiveStep.LabelsPosition.ASIDE));
        controls.addFormItem(mode, "Представление");
        var periodItem = controls.addFormItem(period, "Период");
        var asOfItem = controls.addFormItem(asOf, "На дату"); asOfItem.setVisible(false);
        if (descriptor.periodic()) {
            form.getFilterPanel().add(controls);
        }
        if (descriptor.recorded()) form.setReadOnly(true);
        Map<T, RecorderRefPresentation> pageCaptions = new java.util.WeakHashMap<>();
        if (descriptor.recorded()) {
            var columns = new ArrayList<>(form.getActiveColumns());
            columns.add(ColumnPath.resolve(type, "recorder").withLabel("Регистратор"));
            columns.add(ColumnPath.resolve(type, "lineNumber").withLabel("Строка"));
            columns.add(ColumnPath.resolve(type, "recordingOrder").withLabel("Порядок регистрации"));
            form.setColumnRenderer("recorder", new ComponentRenderer<>(row -> new RecorderRefDisplay(
                pageCaptions.getOrDefault(row, RecorderRefPresentation.unavailable()),
                href -> {
                    var opener = routes.getIfAvailable();
                    if (opener == null || !opener.openAddress(href).opened()) RegisterKeyDialog.showMessage("Документ недоступен");
                })));
            form.setActiveColumns(columns);
        }
        RegisterSliceReader<T> slice = descriptor.periodic() ? readers.slice(type) : null;
        form.setPageReader((specification, filters, values, opaqueContext, pageable, fetchPaths) -> {
            try {
                Page<T> page;
                if (!descriptor.periodic() || "История".equals(mode.getValue())) {
                    Specification<T> history = specification;
                    if (descriptor.periodic() && !period.getValue().isEmpty()) {
                        var range = org.ipro.filtergrid.PeriodFilter.<T>dateTimes(period)
                            .createSpecification(descriptor.period().orElseThrow().name());
                        Specification<T> window = (root, query, cb) -> range.toPredicate(root, query, cb);
                        history = history == null ? window : history.and(window);
                    }
                    page = service.findAll(history, pageable, fetchPaths);
                } else {
                    if (asOf.getValue() == null || asOf.isInvalid()) throw new IllegalArgumentException("Укажите корректную дату среза.");
                    if (opaqueContext) throw new IllegalArgumentException("Произвольный программный отбор не поддерживается в срезе. Используйте историю.");
                    var phases = RegisterListFilterPhases.split(descriptor, filters);
                    Specification<T> before = form.compileFilter(phases.dimensions());
                    Specification<T> after = form.compileFilter(phases.afterSlice());
                    for (var entry : values.entrySet()) {
                        Specification<T> part = form.compileContextFilter(entry.getKey(), entry.getValue());
                        if (descriptor.dimensionNames().stream().anyMatch(name -> entry.getKey().equals(name) || entry.getKey().startsWith(name + ".")))
                            before = before == null ? part : before.and(part);
                        else after = after == null ? part : after.and(part);
                    }
                    page = service.findAll(slice.latestListSpecification(asOf.getValue(), before, after),
                        slice.latestListPageable(pageable), fetchPaths);
                }
                if (descriptor.recorded()) {
                    List<RecorderRef> refs = page.getContent().stream().map(row -> ((RecordedRegisterEntry) row).getRecorder()).toList();
                    List<RecorderRefPresentation> labels = captions.resolve(refs);
                    for (int i = 0; i < refs.size(); i++) pageCaptions.put(page.getContent().get(i), labels.get(i));
                }
                return page;
            } catch (IllegalArgumentException invalid) {
                org.slf4j.LoggerFactory.getLogger(RegisterFormCustomization.class).warn("Не удалось применить отбор списка регистра {}", type.getSimpleName(), invalid);
                RegisterKeyDialog.showMessage(invalid.getMessage());
                return Page.empty(pageable);
            }
        });
        form.setViewOptions(() -> captureOptions(form, mode, asOf, descriptor.periodic()), options -> {
            String restoredMode = options.getOrDefault("register.mode", "История");
            if (!List.of("История", "Срез последних").contains(restoredMode)) throw new IllegalArgumentException("Неизвестный режим списка регистра.");
            LocalDateTime restoredDate = options.containsKey("register.asOf") ? LocalDateTime.parse(options.get("register.asOf")) : asOf.getValue();
            if ("Срез последних".equals(restoredMode) && (!descriptor.periodic() || !options.containsKey("register.asOf")))
                throw new IllegalArgumentException("Сохранённый срез требует периодический регистр и дату.");
            Map<String, Object> restored = new LinkedHashMap<>();
            for (var entry : options.entrySet()) {
                boolean many = entry.getKey().startsWith("register.contextMany.");
                if (!many && !entry.getKey().startsWith("register.context.")) continue;
                String path = entry.getKey().substring(many ? "register.contextMany.".length() : "register.context.".length());
                ColumnPath column = ColumnPath.resolve(type, path);
                restored.put(path, many ? restoreContextValues(column, entry.getValue()) : restoreContextScalar(column, entry.getValue()));
            }
            // Restore only after validating every value. Opening constraints retain priority.
            var combined = new LinkedHashMap<>(restored);
            combined.putAll(form.getOpeningContextFilters());
            asOf.setValue(restoredDate);
            mode.setValue(restoredMode);
            if (!combined.isEmpty()) form.setOpeningContextFilters(combined);
        });
        mode.addValueChangeListener(event -> {
            boolean sliceMode = "Срез последних".equals(event.getValue());
            asOfItem.setVisible(sliceMode); periodItem.setVisible(!sliceMode); reset(form);
        });
        period.addValueChangeListener(event -> reset(form));
        asOf.addValueChangeListener(event -> reset(form));
        if (descriptor.keyChangeAllowed()) form.addAction(new ActionHandler() {
            @Override public ActionDefinition definition() {
                return ActionDefinition.forEntity(new ActionId("change-register-key"), ActionSurface.LIST_TOOLBAR,
                    type, "Изменить ключ записи", "KEY", 50, ActionRequirement.selectionOnly());
            }
            @Override public ActionDecision availability(Object row) {
                return permissions.canChangeKey(type, row) ? ActionDecision.allowed() : ActionDecision.blocked(
                    ActionDecision.Reason.ACCESS_DENIED, "Нет права изменения ключа записи или доступа к выбранной записи.");
            }
            @Override public void execute(ActionInvocation invocation) {
                if (invocation.selected() instanceof BaseEntity row)
                    new RegisterKeyDialog(descriptor, row, context, changes, () -> reset(form)).open();
            }
        });
    }
    private static void reset(ListForm<?, ?> form) {
        form.getGrid().deselectAll();
        form.getGrid().scrollToIndex(0);
        form.refresh();
    }
    private static Map<String, String> captureOptions(ListForm<?, ?> form, ComboBox<String> mode, DateTimePicker asOf, boolean periodic) {
        Map<String, String> options = new LinkedHashMap<>();
        if (periodic) {
            options.put("register.mode", mode.getValue());
            if (asOf.getValue() != null) options.put("register.asOf", asOf.getValue().toString());
        }
        // O7: panel values are not persisted. Reading legacy view options remains compatible.
        // Opening constraints belong to the address, not to editable panel state.
        form.getOpeningContextFilters().forEach((path, value) -> {
            if (value instanceof java.util.Collection<?> collection) {
                try { options.put("register.contextMany." + path, new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(collection.stream().map(RegisterFormCustomization::contextLiteral).toList())); }
                catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalArgumentException("Не удалось сохранить ограничение открытия", failure); }
            } else options.put("register.context." + path, contextLiteral(value));
        });
        return options;
    }

    private static String contextLiteral(Object value) {
        if (value instanceof IdentifiableEntity<?> entity) {
            if (entity.getId() == null) throw new IllegalArgumentException("В отборе выберите сохранённый элемент.");
            return entity.getId().toString();
        }
        return value instanceof Enum<?> enumeration ? enumeration.name() : value.toString();
    }
    private Object restoreContextValues(ColumnPath column, String literal) {
        try {
            var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(literal);
            if (!tree.isArray()) throw new IllegalArgumentException("Список значений отбора должен быть массивом.");
            List<Object> values = new ArrayList<>();
            for (var item : tree) values.add(restoreContextScalar(column, item.asText()));
            return List.copyOf(values);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new IllegalArgumentException("Некорректный отбор списка в сохранённом виде", failure);
        }
    }
    private Object restoreContextScalar(ColumnPath column, String literal) {
        if (IdentifiableEntity.class.isAssignableFrom(column.getJavaType()))
            return access.detail(column.getJavaType(), Long.valueOf(literal))
                .orElseThrow(() -> new IllegalArgumentException("Элемент отбора отсутствует или недоступен. Выберите значение заново."));
        return FilterValueCodec.decode(literal, new FilterFieldResolver.ResolvedFilterField(
            column.getKey(), column.getLabel(), column.getJavaType(), FilterDataType.TEXT, true));
    }
}
