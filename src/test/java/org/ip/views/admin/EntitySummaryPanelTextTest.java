package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import org.ip.model.Nomenclature;
import org.ipro.form.registry.FormType;
import org.ipro.metadata.FactOrigin;
import org.ipro.metadata.MetadataDiagnostic;
import org.ipro.metadata.facet.FacetKey;
import org.ipro.metadata.facet.FacetKind;
import org.ipro.metadata.facet.FactSource;
import org.ipro.metadata.facet.ResolvedValue;
import org.ipro.rls.RlsDimensionKind;
import org.ipro.vaadin.explorer.EntitySummary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3.1 шаг 5: текст карточки. Проверяется то, что раньше было ложью: колонка «Источник» показывала
 * путь к .java-файлу класса сущности для любой строки, то есть называла точное место, которого факт
 * не сообщает. Здесь закреплены три правила: происхождение видно и различимо, пустое место остаётся
 * пустым, а слой переопределения не подменяет происхождение кодового значения.
 *
 * <p>Текст собирается статическими функциями панели — UI для этого не поднимается.</p>
 */
class EntitySummaryPanelTextTest {

    @Test
    void everyOriginHasItsOwnReadableLabel() {
        for (FactOrigin origin : FactOrigin.values()) {
            assertThat(EntitySummaryPanel.originLabel(origin))
                .as("происхождение обязано иметь непустую подпись: %s", origin)
                .isNotBlank();
        }

        Set<String> labels = java.util.Arrays.stream(FactOrigin.values())
            .map(EntitySummaryPanel::originLabel)
            .collect(Collectors.toSet());
        assertThat(labels)
            .as("разные происхождения обязаны читаться по-разному, иначе колонка не различает факты")
            .hasSize(FactOrigin.values().length);
        assertThat(labels)
            .as("подпись — текст для человека, а не имя константы")
            .doesNotContain(java.util.Arrays.stream(FactOrigin.values())
                .map(FactOrigin::name).toArray(String[]::new));
    }

    @Test
    void theTwoStatesThatCarryTheNoInventionRuleKeepTheirWording() {
        assertThat(EntitySummaryPanel.originLabel(FactOrigin.PLATFORM_DEFAULT)).isEqualTo("платформа");
        assertThat(EntitySummaryPanel.originLabel(FactOrigin.UNKNOWN))
            .as("неизвестное происхождение называется неизвестным, а не платформенным")
            .isEqualTo("происхождение неизвестно");
    }

    /**
     * Прежний рендер выводил путь из класса сущности. Пустой символ обязан оставить ячейку без
     * места, а не заполнить её предполагаемым файлом.
     */
    @Test
    void theSourceCellNeverInventsAPath() {
        String derived = EntitySummaryPanel.sourceText(
            ResolvedValue.fact("CATALOG", FactOrigin.DERIVED, ""));
        assertThat(derived).isEqualTo("выведено");
        assertThat(derived).doesNotContain(".java");
        assertThat(EntitySummaryPanel.sourceText(ResolvedValue.code("Выбор номенклатуры")))
            .as("двухаргументный конструктор даёт UNKNOWN, а не платформенный дефолт")
            .isEqualTo("происхождение неизвестно")
            .doesNotContain(".java");
    }

    @Test
    void symbolIsAppendedAndABlankSymbolLeavesNoSeparator() {
        assertThat(EntitySummaryPanel.sourceText(ResolvedValue.fact("STANDARD_ROOT",
            FactOrigin.REGISTRATION, "org.ipro.form.link.FormRouteCatalog#STANDARD_ROOT")))
            .isEqualTo("регистрация · org.ipro.form.link.FormRouteCatalog#STANDARD_ROOT");
        assertThat(EntitySummaryPanel.sourceText(ResolvedValue.fact("Доступна",
            FactOrigin.DERIVED, "   ")))
            .as("пустой символ не оставляет висящий разделитель")
            .isEqualTo("выведено");
        assertThat(EntitySummaryPanel.sourceText(null, FactOrigin.DERIVED, null))
            .as("null-символ — то же пустое место")
            .isEqualTo("выведено");
    }

    @Test
    void anOverrideShowsTheLayerAndTheCodeDefaultItReplaces() {
        String cell = EntitySummaryPanel.sourceText(
            new ResolvedValue("Переопределено", FactSource.OVERRIDE,
                FactOrigin.PLATFORM_DEFAULT, ""));
        assertThat(cell)
            .as("действует переопределение, а происхождение описывает перекрытый код")
            .isEqualTo("переопределение (код: платформа)");
    }

    @Test
    void requiredShowsItsOriginOnlyWhenTheFactIsPresent() {
        assertThat(EntitySummaryPanel.requiredCell(field(true, FactOrigin.BEAN_VALIDATION)))
            .isEqualTo("да · bean-валидация");
        assertThat(EntitySummaryPanel.requiredCell(field(false, FactOrigin.PLATFORM_DEFAULT)))
            .as("у необязательного поля происхождение не рисуется: это ответ на незаданный вопрос")
            .isEmpty();
    }

    @Test
    void theFieldTypeShowsWhereTheTypeComesFrom() {
        assertThat(EntitySummaryPanel.fieldTypeCell(fieldWithType("Строка", FactOrigin.JPA_MAPPING)))
            .isEqualTo("Строка · JPA-маппинг");
        assertThat(EntitySummaryPanel.fieldTypeCell(fieldWithType("", FactOrigin.JAVA_TYPE)))
            .as("без разрешённого типа остаётся происхождение — оно известно")
            .isEqualTo("тип Java");
    }

    @Test
    void theFormSourceShowsTheCheckedSymbolOrTheDeclaringPath() {
        EntitySummary.FormRow checked = new EntitySummary.FormRow(FormType.ITEM, null,
            "кастомная фабрика", false, "",
            FactOrigin.REGISTRATION, "org.ip.views.forms.NomenclatureItemFormConfig");
        EntitySummary.FormRow pathOnly = new EntitySummary.FormRow(FormType.ITEM, null,
            "кастомная фабрика", false, "org/ip/views/forms/NomenclatureItemFormConfig.java");
        EntitySummary.FormRow platform = new EntitySummary.FormRow(FormType.LIST, null,
            "платформенная автогенерация", true, "");

        assertThat(EntitySummaryPanel.formSourceText(checked))
            .isEqualTo("регистрация · org.ip.views.forms.NomenclatureItemFormConfig");
        assertThat(EntitySummaryPanel.formSourceText(pathOnly))
            .as("путь декларанта показывается, когда проверенного символа нет")
            .isEqualTo("регистрация · org/ip/views/forms/NomenclatureItemFormConfig.java");
        assertThat(EntitySummaryPanel.formSourceText(platform))
            .isEqualTo("платформа");
    }

    @Test
    void severityIsSpelledOutForPeople() {
        assertThat(EntitySummaryPanel.severityLabel(MetadataDiagnostic.Severity.ERROR))
            .isEqualTo("ошибка");
        assertThat(EntitySummaryPanel.severityLabel(MetadataDiagnostic.Severity.WARNING))
            .isEqualTo("предупреждение");
        assertThat(EntitySummaryPanel.severityLabel(MetadataDiagnostic.Severity.INFO))
            .isEqualTo("сведения");
    }

    @Test
    void theDiagnosticAddressKeepsTheFieldWhenThereIsOne() {
        assertThat(EntitySummaryPanel.diagnosticAddress(diagnostic("org.ip.model.Nomenclature", "code")))
            .isEqualTo("org.ip.model.Nomenclature#code");
        assertThat(EntitySummaryPanel.diagnosticAddress(diagnostic("org.ip.model.Nomenclature", "")))
            .as("диагностика уровня сущности остаётся адресом сущности, без выдуманного поля")
            .isEqualTo("org.ip.model.Nomenclature");
    }

    @Test
    void diagnosticsAndLifecycleAreDrawnFromTheSummary() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);

        panel.show(summary(List.of(lifecycleHandler()), List.of(diagnostic("org.ip.model.Nomenclature", "code"))));

        assertThat(sections(panel))
            .as("разделы приходят из сводки, а не из карточки")
            .containsExactly("Диагностика", "Lifecycle");
    }

    @Test
    void aSummaryWithoutLifecycleOrDiagnosticsDrawsNoSuchSections() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);

        panel.show(summary(List.of(), List.of()));

        assertThat(sections(panel))
            .as("«записей нет» не выдаётся за «есть пустой раздел»")
            .isEmpty();
    }

    // ------------------------------------------------------------ адрес диагностики (6.2)

    /**
     * E3.2.1 шаг 6.2: «Где» — адрес записи в самой карточке. Замер владельца даёт ключ лишь трём
     * видам: цель выбора и измерение RLS показываются одним разделом, поэтому место — вкладка и её
     * единственный раздел (повтор названия вкладки в имени раздела не называется отдельно).
     */
    @Test
    void thePlaceOfADiagnosticIsItsAspectsTabAndTheOnlySectionShowingIt() {
        EntitySummary lookup = summaryWithDiagnostics(List.of(), List.of(), List.of(
            diagnosticOf(FacetKind.LOOKUP_TARGET, "nomenclature", "REFERENCE_TARGET_NOT_METADATA")));
        CardSection.Location lookupPlace = CardSection.locate(lookup, lookup.diagnostics().get(0));
        assertThat(lookupPlace.tab()).isEqualTo(CardTab.LINKS);
        assertThat(lookupPlace.section()).isEqualTo(CardSection.section("targets"));
        assertThat(EntitySummaryPanel.placeText(lookupPlace)).isEqualTo("Связи");

        EntitySummary rls = summaryWithDiagnostics(List.of(), List.of(), List.of(
            diagnosticOf(FacetKind.RLS_DIMENSION, "", "RLS_SCAN")));
        CardSection.Location rlsPlace = CardSection.locate(rls, rls.diagnostics().get(0));
        assertThat(rlsPlace.tab()).isEqualTo(CardTab.ACCESS);
        assertThat(rlsPlace.section()).isEqualTo(CardSection.section("dimensions"));
        assertThat(EntitySummaryPanel.placeText(rlsPlace)).isEqualTo("Доступ");
    }

    /**
     * Строение поля показывают две секции, поэтому раздел называется только тогда, когда поле нашлось
     * ровно в одной проекции. Найденное в обеих (обычное дело: поле формы видно и в гриде) или не
     * найденное вовсе оставляет вкладку без раздела: предпочесть одну проекцию значило бы выдать
     * выбор карточки за факт.
     */
    @Test
    void fieldStructureNamesASectionOnlyWhenExactlyOneProjectionShowsTheField() {
        EntitySummary formOnly = summaryWithDiagnostics(List.of(namedField("number")), List.of(),
            List.of(diagnosticOf(FacetKind.FIELD_STRUCTURE, "number", "REDUNDANT_REQUIRED")));
        assertThat(place(formOnly)).isEqualTo("Поля и колонки · Поля — форма");

        EntitySummary gridOnly = summaryWithDiagnostics(List.of(), List.of(namedField("date")),
            List.of(diagnosticOf(FacetKind.FIELD_STRUCTURE, "date", "REDUNDANT_TYPE")));
        assertThat(place(gridOnly)).isEqualTo("Поля и колонки · Поля — грид");

        EntitySummary both = summaryWithDiagnostics(List.of(namedField("number")),
            List.of(namedField("number")),
            List.of(diagnosticOf(FacetKind.FIELD_STRUCTURE, "number", "REDUNDANT_REQUIRED")));
        assertThat(CardSection.locateAll(both, both.diagnostics().get(0)))
            .extracting(EntitySummaryPanel::placeText)
            .containsExactly("Поля и колонки · Поля — форма", "Поля и колонки · Поля — грид");

        EntitySummary nowhere = summaryWithDiagnostics(List.of(), List.of(),
            List.of(diagnosticOf(FacetKind.FIELD_STRUCTURE, "missing", "TYPE_CONFLICT")));
        assertThat(place(nowhere)).isEqualTo("Поля и колонки");
    }

    /**
     * Записи без ключа принадлежат уровню сущности, и это не то же самое, что «вид не размещён»:
     * у второй записи ключ есть, но раздела под неё в карточке не заведено — назвать её уровнем
     * сущности значило бы объявить отсутствие раздела отсутствием факта.
     */
    @Test
    void aKeylessRecordIsTheEntityLevelAndAnUnknownKindIsNot() {
        EntitySummary summary = summaryWithDiagnostics(List.of(), List.of(), List.of(
            diagnostic("org.ip.model.Nomenclature", "code"),
            diagnosticOf(FacetKind.GRID_COLUMN_HEADER, "code", "FUTURE_DIAGNOSTIC_CODE")));

        CardSection.Location entityLevel = CardSection.locate(summary, summary.diagnostics().get(0));
        assertThat(entityLevel.addressed()).isFalse();
        assertThat(EntitySummaryPanel.placeText(entityLevel)).isEqualTo("уровень сущности");

        CardSection.Location unplaced = CardSection.locate(summary, summary.diagnostics().get(1));
        assertThat(unplaced.tab()).isNull();
        assertThat(unplaced.addressed())
            .as("ключ есть — запись адресована, но раздел ей не объявлен")
            .isTrue();
        assertThat(EntitySummaryPanel.placeText(unplaced)).isEqualTo("вид не размещён");
    }

    // ---------------------------------------------------------------- разделы действий (5.1)

    /**
     * E3.2.0 шаг 5.1: разделы действий приходят из сводки. Объявление и исполнитель — два раздела,
     * потому что это два разных факта: строка объявления остаётся строкой регистрации и без handler'а.
     */
    @Test
    void theActionSectionsAreDrawnFromTheSummaryAndStayTwoSeparateTables() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);

        panel.show(summaryWithActions(List.of(actionDeclaration(false), actionExecutor(false))));

        assertThat(sections(panel))
            .as("определение без handler'а отличается от действия без объявления")
            .containsExactly("Действия", "Действия — исполнители");
    }

    @Test
    void aKindOfFactWithoutRowsDrawsNoSectionOfItsOwn() {
        EntitySummaryPanel onlyDeclarations = new EntitySummaryPanel(null);
        onlyDeclarations.show(summaryWithActions(List.of(actionDeclaration(true))));
        assertThat(sections(onlyDeclarations)).containsExactly("Действия");

        EntitySummaryPanel noActions = new EntitySummaryPanel(null);
        noActions.show(summaryWithActions(List.of()));
        assertThat(sections(noActions))
            .as("пустой аспект не изображается разделом")
            .isEmpty();
    }

    @Test
    void aSurfaceReadsAsWordsAndNotAsAnEnumName() {
        List<String> labels = java.util.Arrays.stream(org.ipro.form.action.ActionSurface.values())
            .map(surface -> EntitySummaryPanel.actionSurfaceLabel(actionRow(surface, null)))
            .toList();

        assertThat(labels).doesNotContain(java.util.Arrays.stream(
                org.ipro.form.action.ActionSurface.values())
            .map(Enum::name).toArray(String[]::new));
        assertThat(labels).doesNotContain("")
            .contains("тулбар списка", "подвал карточки", "меню карточки");
    }

    /** Вариант `null` в ключе — default-вариант поверхности, а не «варианта нет»: пустое место. */
    @Test
    void theVariantCellLeavesTheDefaultVariantBlankAndNamesANamedOne() {
        assertThat(EntitySummaryPanel.actionVariantCell(
            actionRow(org.ipro.form.action.ActionSurface.ITEM_FOOTER, null))).isEqualTo("—");
        assertThat(EntitySummaryPanel.actionVariantCell(
            actionRow(org.ipro.form.action.ActionSurface.ITEM_FOOTER, "short"))).isEqualTo("short");
    }

    /** Исполнитель — факт владельца: карточка печатает его текст, а не выносит свой вердикт. */
    @Test
    void theExecutorCellPrintsTheOwnersWording() {
        assertThat(EntitySummaryPanel.executorCell(actionExecutor(true))).isEqualTo("найден");
        assertThat(EntitySummaryPanel.executorCell(actionExecutor(false))).isEqualTo("не найден");
        assertThat(EntitySummaryPanel.executorCell(actionExecutor(false)))
            .as("«нет» вместо «не найден» стёрло бы разницу между объявлением и исполнителем")
            .isNotEqualTo("нет");
    }

    // ---------------------------------------------------------------- сценарии чтения (5.2)

    /**
     * E3.2.0 шаг 5.2: сценарий и его пути — два раздела, а не один: у сценария есть допуск и
     * счётчик путей, у пути — причина, и общая таблица оставляла бы у строки пути пустой «Допущен».
     */
    @Test
    void theReadPlanSectionsAreDrawnFromTheSummaryAndStayTwoSeparateTables() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);

        panel.show(summaryWithReadPlans(List.of(
            readPlan("LIST", true, 1, List.of(path("LIST", "id", "metadata:LIST"))))));

        assertThat(sections(panel))
            .as("планы и пути — факты одного аспекта, но строки у них разные")
            .containsExactly("Сценарии чтения", "Сценарии чтения — пути");
    }

    @Test
    void aScenarioWithoutPathsDrawsAnExplicitZeroPathState() {
        EntitySummaryPanel allowedWithoutPaths = new EntitySummaryPanel(null);
        allowedWithoutPaths.show(summaryWithReadPlans(
            List.of(readPlan("LIST", true, 0, List.of()))));
        assertThat(sections(allowedWithoutPaths))
            .as("«допущен, путей нет» — факт плана: раздел есть, а путей у него нет")
            .containsExactly("Сценарии чтения", "Сценарии чтения — пути");
        assertThat(CardSections.textIn(allowedWithoutPaths))
            .contains("LIST: 0 путей загрузки", "набор сценариев объявлен приложением");

        EntitySummaryPanel noPlans = new EntitySummaryPanel(null);
        noPlans.show(summaryWithReadPlans(List.of()));
        assertThat(sections(noPlans))
            .as("пустой аспект не изображается разделом")
            .isEmpty();
    }

    @Test
    void anUnavailableReadInspectionIsShownWithoutClaimingZeroPaths() {
        EntitySummary base = summaryWithReadPlans(List.of());
        EntitySummary unavailable = copyWithSectionsAndInspection(base, base.tableSections(), false);
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(unavailable);
        assertThat(sections(panel)).containsExactly("Сценарии чтения", "Сценарии чтения — пути");
        assertThat(CardSections.textIn(panel)).contains("Инспекция планов чтения не подключена")
            .doesNotContain("0 путей загрузки");
        assertThat(CardSection.section("paths").hasRows(unavailable)).isTrue();
    }

    @Test
    void bothFieldProjectionsOfferSeparateWorkingButtons() {
        EntitySummary base = summaryWithDiagnostics(List.of(namedField("number")),
            List.of(namedField("number")),
            List.of(diagnosticOf(FacetKind.FIELD_STRUCTURE, "number", "REDUNDANT_REQUIRED")));
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(base);
        java.util.ArrayList<String> places = new java.util.ArrayList<>();
        panel.setPlaceListener(places::add);
        Component cell = panel.diagnosticWhereCell(base, base.diagnostics().get(0));
        List<Button> buttons = components(cell).filter(Button.class::isInstance)
            .map(Button.class::cast).toList();
        assertThat(buttons).hasSize(2);
        buttons.get(0).click();
        buttons.get(1).click();
        assertThat(places).containsExactly("fields/form", "fields/grid");
        assertThat(CardSections.details(panel, "Поля — форма").isOpened()).isTrue();
        assertThat(CardSections.details(panel, "Поля — грид").isOpened()).isTrue();
    }

    static class Alpha { static class Row {} }
    static class Beta { static class Row {} }

    @Test
    void ownedDiagnosticsUseTheFullTypeIdentityAndNameTheField() {
        EntitySummary.DiagnosticRow row = new EntitySummary.DiagnosticRow(
            MetadataDiagnostic.Severity.WARNING, "TYPE_CONFLICT", Alpha.Row.class.getName(), "number",
            "", FacetKey.of(FacetKind.FIELD_STRUCTURE, Alpha.Row.class, "number"),
            ResolvedValue.code("conflict"), "fixture");
        EntitySummary base = summaryWithDiagnostics(List.of(namedField("number")), List.of(), List.of(row));
        EntitySummary owned = copyWithSectionsAndInspection(base, List.of(
            new EntitySummary.SectionRow(FacetKey.of(FacetKind.TABLE_SECTION, Nomenclature.class, "first"),
                ResolvedValue.fact("Первая", FactOrigin.EXPLICIT, Alpha.Row.class.getName()), "Row", 1, 0, 0, 0),
            new EntitySummary.SectionRow(FacetKey.of(FacetKind.TABLE_SECTION, Nomenclature.class, "second"),
                ResolvedValue.fact("Вторая", FactOrigin.EXPLICIT, Beta.Row.class.getName()), "Row", 2, 0, 0, 0)), true);
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(owned);
        Component cell = panel.diagnosticWhereCell(owned, row);
        assertThat(CardSections.textIn(cell)).contains("Табличные части", "Первая · number")
            .doesNotContain("Вторая", "Поля — форма");
        java.util.ArrayList<String> places = new java.util.ArrayList<>();
        panel.setPlaceListener(places::add);
        CardSections.buttonIn(cell).click();
        assertThat(places).containsExactly("fields/table-sections");
        assertThat(CardSections.textIn(panel)).contains("Первая · number");
    }

    private static java.util.stream.Stream<Component> components(Component root) {
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(root),
            root.getChildren().flatMap(EntitySummaryPanelTextTest::components));
    }

    private static EntitySummary copyWithSectionsAndInspection(EntitySummary base,
            List<EntitySummary.SectionRow> sections, boolean inspection) {
        return new EntitySummary(base.entityClass(), base.simpleName(), base.displayName(), base.overview(),
            base.fieldsForm(), base.fieldsGrid(), base.listColumns(), base.selectColumns(), sections,
            base.forms(), base.contextFilters(), base.selections(), base.references(), base.numbering(),
            base.lifecycle(), base.diagnostics(), base.actions(), base.readPlans(), base.accessRows(),
            base.lookupTargets(), inspection);
    }

    /** Допуск — текст владельца плана: «нет» стёрло бы разницу между отказом и отсутствием сценария. */
    @Test
    void theAdmissionCellPrintsTheOwnersWording() {
        assertThat(EntitySummaryPanel.readPlanAdmissionCell(readPlan("LIST", true, 0, List.of())))
            .isEqualTo("допущен");
        assertThat(EntitySummaryPanel.readPlanAdmissionCell(readPlan("UPDATE", false, 0, List.of())))
            .as("отказ сценария виден словом владельца, а не пустой ячейкой")
            .isEqualTo("не допущен");
    }

    /** Счётчик читается числом, в том числе у допущенного сценария с пустым планом. */
    @Test
    void thePathCountIsACounterAndNotABlankCell() {
        assertThat(EntitySummaryPanel.readPlanPathCountCell(readPlan("LIST", true, 0, List.of())))
            .as("пустая ячейка означала бы «неизвестно», а не «путей нет»")
            .isEqualTo("0");
        assertThat(EntitySummaryPanel.readPlanPathCountCell(readPlan("LOOKUP", true, 2, List.of(
            path("LOOKUP", "code", "metadata:LOOKUP"), path("LOOKUP", "name", "instance-name")))))
            .isEqualTo("2");
    }

    /** Причина пути — код плана как есть: пересказ стёр бы, кто добавил путь. */
    @Test
    void thePathReasonIsPrintedAsTheOwnerGaveIt() {
        assertThat(EntitySummaryPanel.pathReasonCell(path("LIST", "id", "metadata:LIST")))
            .isEqualTo("metadata:LIST");
        assertThat(EntitySummaryPanel.pathReasonCell(path("LIST", "id", "")))
            .as("пустая причина остаётся пустым местом, а не догадкой")
            .isEqualTo("—");
    }

    // ---------------------------------------------------------------- доступ (5.3)

    /**
     * E3.2.0 шаг 5.3: измерение и правило значения — два раздела: у измерения есть род и каталог
     * грантов, у пути — своё «null значит не применимо», и общая таблица оставляла бы у строки
     * правила пустые «Род» и «Гранты» (ложь «род неизвестен»).
     */
    @Test
    void theAccessSectionsAreDrawnFromTheSummaryAndStayTwoSeparateTables() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);

        panel.show(summaryWithAccess(List.of(accessRow("BRANCH", RlsDimensionKind.FILTERABLE, true,
            List.of(accessRule("BRANCH", "id", false))))));

        assertThat(sections(panel))
            .as("измерения и правила значений — строки разных фактов")
            .containsExactly("Доступ", "Доступ — правила значений");
    }

    @Test
    void aDimensionWithoutRulesDrawsTheDimensionSectionOnly() {
        EntitySummaryPanel customPolicy = new EntitySummaryPanel(null);
        customPolicy.show(summaryWithAccess(List.of(
            accessRow("JOURNAL", RlsDimensionKind.FILTERABLE, true, List.of()))));
        assertThat(sections(customPolicy))
            .as("у сложной политики правил нет — это факт, а не потерянные строки")
            .containsExactly("Доступ");

        EntitySummaryPanel noAccess = new EntitySummaryPanel(null);
        noAccess.show(summaryWithAccess(List.of()));
        assertThat(sections(noAccess))
            .as("пустой аспект не изображается разделом")
            .isEmpty();
    }

    /** Род измерения — словарь платформы словами: «фильтруемое»/«проверяемое», не имена констант. */
    @Test
    void theDimensionKindReadsAsWordsAndNotAsEnumNames() {
        assertThat(EntitySummaryPanel.accessKindLabel(
            accessRow("BRANCH", RlsDimensionKind.FILTERABLE, false, List.of())))
            .isEqualTo("фильтруемое");
        assertThat(EntitySummaryPanel.accessKindLabel(
            accessRow("ENTITY:Nomenclature", RlsDimensionKind.CHECK_ONLY, false, List.of())))
            .as("ворота отличаются от фильтра словом, а не константой `CHECK_ONLY`")
            .isEqualTo("проверяемое")
            .isNotEqualTo(RlsDimensionKind.CHECK_ONLY.name());
    }

    /** «Гранты» — признак каталога, а не значения: гранты выданы пользователям, а не объявлены типом. */
    @Test
    void theGrantCatalogueCellIsAPresenceMarkerAndNothingMore() {
        assertThat(EntitySummaryPanel.grantCatalogCell(
            accessRow("BRANCH", RlsDimensionKind.FILTERABLE, true, List.of())))
            .isEqualTo("да");
        assertThat(EntitySummaryPanel.grantCatalogCell(
            accessRow("BRANCH", RlsDimensionKind.FILTERABLE, false, List.of())))
            .as("пустое место — домовая конвенция «нет»")
            .isEmpty();
    }

    /** Null в пути: печатается только объявленное «измерение не применимо», иначе пусто. */
    @Test
    void theNullsNotApplicableCellNamesWhatTheDeclarationMeans() {
        assertThat(EntitySummaryPanel.nullsNotApplicableCell(
            accessRule("BRANCH", "branch.id", true)))
            .isEqualTo("измерение не применимо");
        assertThat(EntitySummaryPanel.nullsNotApplicableCell(
            accessRule("BRANCH", "branch.id", false)))
            .as("необъявленное не выдаётся за «применимо»")
            .isEmpty();
    }

    // ---------------------------------------------------------------- связи (5.4)

    /**
     * E3.2.0 шаг 5.4: раздел «Связи» приходит из сводки, а цель несёт происхождение существующим
     * `lookupCellText`. Пустой аспект раздела не рисует: «ссылок нет» и «ссылка без цели» — разные
     * факты, и второй тоже не даёт строки.
     */
    @Test
    void theLookupSectionIsDrawnFromTheSummaryAndAnEmptyAspectDrawsNothing() {
        EntitySummaryPanel panel = new EntitySummaryPanel(null);
        panel.show(summaryWithLookups(List.of(lookupRow("Nomenclature", FactOrigin.EXPLICIT, ""))));
        assertThat(sections(panel)).containsExactly("Связи");

        EntitySummaryPanel noLookups = new EntitySummaryPanel(null);
        noLookups.show(summaryWithLookups(List.of()));
        assertThat(sections(noLookups))
            .as("пустой аспект не изображается разделом")
            .isEmpty();
    }

    // ---------------------------------------------------------------- фикстуры

    private static List<String> sections(Component root) {
        return CardSections.titles(root);
    }

    /** Сводка одного аспекта: остальные разделы пусты — так видно, какие секции добавил аспект. */
    private static EntitySummary summaryWithActions(List<EntitySummary.ActionRow> actions) {
        return new EntitySummary(Nomenclature.class, "Nomenclature",
            ResolvedValue.code("Номенклатура"),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), actions,
            List.of(), List.of(), List.of());
    }

    private static EntitySummary.ActionRow actionDeclaration(boolean visible) {
        return new EntitySummary.ActionRow(
            FacetKey.of(FacetKind.ACTION, Nomenclature.class, "LIST_TOOLBAR/crud.create", null),
            org.ipro.form.action.ActionSurface.LIST_TOOLBAR, "crud.create", "Создать", 10,
            visible, false, false,
            ResolvedValue.fact("Создать", FactOrigin.REGISTRATION,
                "org.ip.config.ActionPolicyConfig"),
            visible ? "" : "подавлено приложением");
    }

    private static EntitySummary.ActionRow actionExecutor(boolean found) {
        return new EntitySummary.ActionRow(
            FacetKey.of(FacetKind.ACTION_HANDLER, Nomenclature.class, "LIST_TOOLBAR/crud.create", null),
            org.ipro.form.action.ActionSurface.LIST_TOOLBAR, "crud.create", "", 0, true, false, found,
            ResolvedValue.fact(found ? "найден" : "не найден", FactOrigin.PLATFORM_DEFAULT, ""),
            found ? "" : "исполнитель не зарегистрирован");
    }

    /** Сводка одного аспекта: остальные разделы пусты — так видно, какие секции добавил аспект. */
    private static EntitySummary summaryWithReadPlans(List<EntitySummary.ReadPlanRow> readPlans) {
        return new EntitySummary(Nomenclature.class, "Nomenclature",
            ResolvedValue.code("Номенклатура"),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            readPlans, List.of(), List.of());
    }

    private static EntitySummary.ReadPlanRow readPlan(String scenario, boolean allowed,
                                                      int pathCount,
                                                      List<EntitySummary.PathRow> paths) {
        return new EntitySummary.ReadPlanRow(
            FacetKey.of(FacetKind.FETCH_PLAN, Nomenclature.class, scenario),
            scenario, allowed, pathCount,
            ResolvedValue.fact(allowed ? "допущен" : "не допущен", FactOrigin.REGISTRATION,
                "org.ip.config.EntityClassificationConfig"),
            allowed ? "набор сценариев объявлен приложением"
                : "набор сценариев объявлен приложением; canonical path сценарий не допускает",
            paths);
    }

    private static EntitySummary.PathRow path(String scenario, String attributePath, String reason) {
        return new EntitySummary.PathRow(
            FacetKey.of(FacetKind.FETCH_PLAN_PATH, Nomenclature.class,
                scenario + "/" + attributePath),
            scenario, attributePath, reason,
            ResolvedValue.fact(attributePath, FactOrigin.DERIVED, ""));
    }

    /** Сводка одного аспекта: остальные разделы пусты — так видно, какие секции добавил аспект. */
    private static EntitySummary summaryWithAccess(List<EntitySummary.AccessRow> accessRows) {
        return new EntitySummary(Nomenclature.class, "Nomenclature",
            ResolvedValue.code("Номенклатура"),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), accessRows, List.of());
    }

    private static EntitySummary.AccessRow accessRow(String dimension, RlsDimensionKind kind,
                                                     boolean grantCatalog,
                                                     List<EntitySummary.AccessRuleRow> rules) {
        return new EntitySummary.AccessRow(
            FacetKey.of(FacetKind.RLS_DIMENSION, Nomenclature.class, dimension),
            dimension, kind, grantCatalog,
            ResolvedValue.fact(dimension, FactOrigin.EXPLICIT, "org.ip.model.Nomenclature"),
            "фильтруется @Filter с тем же именем; read- и write-предикаты сверены при старте",
            rules);
    }

    /** Сводка одного аспекта: остальные разделы пусты — так видно, какие секции добавил аспект. */
    private static EntitySummary summaryWithLookups(List<EntitySummary.LookupRow> lookupTargets) {
        return new EntitySummary(Nomenclature.class, "Nomenclature",
            ResolvedValue.code("Номенклатура"),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), lookupTargets);
    }

    private static EntitySummary.AccessRuleRow accessRule(String dimension, String path,
                                                          boolean nullsNotApplicable) {
        return new EntitySummary.AccessRuleRow(
            FacetKey.of(FacetKind.RLS_VALUE_RULE, Nomenclature.class, dimension + "/" + path),
            dimension, path, nullsNotApplicable,
            ResolvedValue.fact(path, FactOrigin.EXPLICIT, "org.ip.model.Nomenclature"));
    }

    private static EntitySummary.ActionRow actionRow(org.ipro.form.action.ActionSurface surface,
                                                     String variant) {
        return new EntitySummary.ActionRow(
            FacetKey.of(FacetKind.ACTION, Nomenclature.class, surface.name() + "/crud.create", variant),
            surface, "crud.create", "Создать", 10, true, false, false,
            ResolvedValue.fact("Создать", FactOrigin.REGISTRATION,
                "org.ip.config.ActionPolicyConfig"),
            "");
    }

    private static EntitySummary summary(List<EntitySummary.LifecycleRow> lifecycle,
                                         List<EntitySummary.DiagnosticRow> diagnostics) {
        return new EntitySummary(Nomenclature.class, "Nomenclature",
            ResolvedValue.code("Номенклатура"),
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(), List.of(), lifecycle, diagnostics);
    }

    private static EntitySummary.LifecycleRow lifecycleHandler() {
        return new EntitySummary.LifecycleRow(
            FacetKey.of(FacetKind.ENTITY_LIFECYCLE_HANDLER, Nomenclature.class), null, true,
            ResolvedValue.fact("NomenclatureLifecycle", FactOrigin.REGISTRATION,
                "org.ip.application.catalog.NomenclatureLifecycle"),
            "другие listeners и делегаты не анализируются");
    }

    /** Место одной-единственной записи сводки — текст колонки «Где». */
    private static String place(EntitySummary summary) {
        EntitySummary.DiagnosticRow row = summary.diagnostics().get(0);
        return EntitySummaryPanel.placeText(CardSection.locate(summary, row));
    }

    /** Сводка, у которой живут только диагностика и поля: остальные разделы пусты. */
    private static EntitySummary summaryWithDiagnostics(List<EntitySummary.FieldRow> formFields,
                                                        List<EntitySummary.FieldRow> gridFields,
                                                        List<EntitySummary.DiagnosticRow> diagnostics) {
        return new EntitySummary(Nomenclature.class, "Nomenclature",
            ResolvedValue.code("Номенклатура"),
            List.of(),
            formFields, gridFields,
            List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(), List.of(), List.of(),
            diagnostics,
            List.of(), List.of(), List.of(), List.of());
    }

    private static EntitySummary.FieldRow namedField(String name) {
        return new EntitySummary.FieldRow(
            FacetKey.of(FacetKind.FIELD_LABEL, Nomenclature.class, name),
            name, "Строка",
            ResolvedValue.fact(name, FactOrigin.EXPLICIT, "org.ip.model.Nomenclature#" + name),
            false, false, FactOrigin.PLATFORM_DEFAULT, FactOrigin.JPA_MAPPING);
    }

    /** Запись с ключом владельца: пустое имя поля — ключ без поля (отказ сканирования измерений). */
    private static EntitySummary.DiagnosticRow diagnosticOf(FacetKind kind, String fieldName,
                                                            String code) {
        FacetKey key = fieldName.isEmpty()
            ? FacetKey.of(kind, Nomenclature.class)
            : FacetKey.of(kind, Nomenclature.class, fieldName);
        return new EntitySummary.DiagnosticRow(MetadataDiagnostic.Severity.WARNING, code,
            Nomenclature.class.getName(), fieldName,
            "WARNING [" + code + "]", key,
            ResolvedValue.fact("запись владельца метаданных", FactOrigin.DERIVED, ""), "");
    }

    private static EntitySummary.DiagnosticRow diagnostic(String entityFqn, String fieldName) {
        return new EntitySummary.DiagnosticRow(MetadataDiagnostic.Severity.WARNING,
            "UI_OPTIONAL_SERVER_REQUIRED", entityFqn, fieldName,
            "WARNING [UI_OPTIONAL_SERVER_REQUIRED]", null,
            ResolvedValue.fact("поле необязательно в UI и обязательно на сервере", FactOrigin.DERIVED, ""),
            "org/ip/model/Nomenclature.java");
    }

    /**
     * E3.2.0 шаг 4.3: ячейка Lookup показывает цель <b>вместе с происхождением</b>. До шага 4 она
     * печатала текст цели, собранный в строке поля, и объявленная цель читалась так же, как
     * выведенная из типа ассоциации.
     */
    @Test
    void theLookupCellNamesTheTargetTogetherWithWhereItComesFrom() {
        assertThat(EntitySummaryPanel.lookupCellText(
            lookupRow("Nomenclature", FactOrigin.EXPLICIT, "")))
            .isEqualTo("Nomenclature · явно");
        assertThat(EntitySummaryPanel.lookupCellText(
            lookupRow("Nomenclature", FactOrigin.JPA_MAPPING, "")))
            .as("выведенная цель не читается как объявленная")
            .isEqualTo("Nomenclature · JPA-маппинг");
    }

    @Test
    void theLookupCellKeepsTheVariantApartFromTheTargetItself() {
        assertThat(EntitySummaryPanel.lookupCellText(
            lookupRow("Journal", FactOrigin.EXPLICIT, "short")))
            .isEqualTo("Journal [short] · явно");
    }

    /** Цели нет — ячейка остаётся пустой: «?» был бы догадкой за владельца метаданных. */
    @Test
    void theLookupCellNeverInventsATarget() {
        String text = EntitySummaryPanel.lookupCellText(lookupRow("", FactOrigin.EXPLICIT, ""));
        assertThat(text).isEqualTo("явно");
        assertThat(text).doesNotContain("?");
    }

    private static EntitySummary.LookupRow lookupRow(String target, FactOrigin origin,
                                                     String variant) {
        return new EntitySummary.LookupRow(
            FacetKey.of(FacetKind.LOOKUP_TARGET, Nomenclature.class, "nomenclature"),
            "nomenclature",
            ResolvedValue.fact(target, origin, "org.ip.model.Nomenclature#nomenclature"),
            Nomenclature.class, variant, "");
    }

    private static EntitySummary.FieldRow field(boolean required, FactOrigin requiredOrigin) {
        return new EntitySummary.FieldRow(FacetKey.of(FacetKind.FIELD_LABEL, Nomenclature.class, "code"),
            "code", "Строка", ResolvedValue.fact("Код", FactOrigin.EXPLICIT, "org.ip.model.Nomenclature#code"),
            required, false, requiredOrigin, FactOrigin.JPA_MAPPING);
    }

    private static EntitySummary.FieldRow fieldWithType(String typeLabel, FactOrigin typeOrigin) {
        return new EntitySummary.FieldRow(FacetKey.of(FacetKind.FIELD_LABEL, Nomenclature.class, "code"),
            "code", typeLabel, ResolvedValue.fact("Код", FactOrigin.EXPLICIT, "org.ip.model.Nomenclature#code"),
            false, false, FactOrigin.PLATFORM_DEFAULT, typeOrigin);
    }
}
