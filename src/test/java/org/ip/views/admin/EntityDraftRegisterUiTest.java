package org.ip.views.admin;

import com.vaadin.flow.component.*;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.server.*;
import com.vaadin.flow.server.streams.DownloadEvent;
import org.ipro.design.*;
import org.ipro.design.generation.*;
import org.ipro.design.json.DraftJsonCodec;
import org.junit.jupiter.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.ip.views.admin.EntityDraftEditorViewTest.*;

class EntityDraftRegisterUiTest {
    private static final UUID DIM = new UUID(41, 1), RES = new UUID(41, 2);
    private UI ui; // CurrentInstance uses weak references; keep the test UI alive across allocations/GC.
    @Test void explicitRlsControlsRoundTripAndPolicyEditsInvalidateTheDeliveredPreview() throws Exception {
        var plain=draft("DAY");var fields=new ArrayList<>(plain.fields());
        fields.set(0,new FieldDraft(DIM,"item","Номенклатура","REFERENCE",DraftText.auto(),true,FieldDraft.UiRequired.AUTO,
            ConstraintDraft.empty(),new ReferenceDraft("com.example.Item"),FieldApplications.root()));
        var draft=new EntityDraft(4,RecipeCatalog.INDEPENDENT_REGISTER_RLS,plain.draftId(),plain.entity(),fields,List.of(),null,
            plain.register().withRls(new RegisterRlsDraft("REGISTER_ITEM",DIM)));
        var base=context();var facts=new HashMap<>(base.runtimeSnapshot().prerequisites());
        facts.put("RLS_NUMERIC_REFERENCE_FILTERS",new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE,"owner fixture"));
        var target=new ReferenceTargetDescriptor("com.example.Item","Item","STANDARD_ROOT","java.lang.Long",
            ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Fact.YES,ReferenceTargetDescriptor.Eligibility.SUPPORTED,"");
        var context=new ProjectDesignContext(base.projectId(),base.version(),base.defaultPackage(),base.existingTypes(),base.existingTables(),
            base.subsystemIds(),base.iconIds(),Map.of(target.typeId(),target),true,new RuntimeDesignSnapshot("rls-ui",true,Map.of(),facts));
        var v=editor(draft);v.setContextSource(() -> context);v.activate();
        assertThat(new DraftJsonCodec().decode(v.prepareExport().bytes())).isEqualTo(draft);
        button(v,"Показать Java").click();var token=v.javaExport();
        assertThat(token.plan().generatorVersion()).isEqualTo("register-java-v2");
        assertThat(token.plan().files().getFirst().content()).contains("@RlsDimension", "applyToLoadByKey = true");
        text(v,"register.rls.dimension").setValue("OTHER_ITEM");
        assertThat(v.session().generation()).isEmpty();
        var refused=event(new ByteArrayOutputStream());v.javaDownloadHandler(token).handleDownloadRequest(refused);
        verify(refused.getResponse()).setStatus(409);
        select(v,"register.period").setValue("NONE");
        assertThat(v.session().draft().register().rls()).isEqualTo(new RegisterRlsDraft("OTHER_ITEM",DIM));
        button(v,"Показать Java").click();assertThat(v.javaExport().plan().planId()).isNotEqualTo(token.plan().planId());
        v.session().selectField(DIM);v.session().removeSelected();button(v,"Проверить").click();
        assertThat(v.session().draft().register().rls().fieldId()).isEqualTo(DIM);
        assertThat(v.session().validation().orElseThrow().problems()).anyMatch(p -> p.code()==DraftProblem.Code.REGISTER_RLS_INVALID);
    }
    @Test void newRlsRecipeIsAnExplicitChoiceAndDoesNotUpgradeAnExistingDraft() {
        var v=editor(null);button(v,"Регистр сведений с RLS").click();
        assertThat(v.session().draft().schemaVersion()).isEqualTo(4);
        assertThat(v.session().draft().recipe()).isEqualTo(RecipeCatalog.INDEPENDENT_REGISTER_RLS);
        assertThat(v.session().draft().register().rls()).isEqualTo(new RegisterRlsDraft("",null));
        var plain=editor(draft("NONE"));assertThat(plain.session().draft().schemaVersion()).isEqualTo(3);
        assertThat(plain.session().draft().register().rls()).isNull();
    }
    @BeforeEach void setup() { principal("admin", true); ui = uiWithResources(); UI.setCurrent(ui); }
    @AfterEach void cleanup() { UI.setCurrent(null); ui = null; org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
    static ProjectDesignContext context() {
        var base = DraftEditorSessionTest.context("register");
        var facts = new HashMap<>(base.runtimeSnapshot().prerequisites());
        var available = new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.AVAILABLE, "test");
        for (var key : List.of("REGISTER_FACTS", "ET6_ET7_REGISTER_CLASSIFICATION", "REGISTER_ENGINE_OPERATIONS")) facts.put(key, available);
        return new ProjectDesignContext(base.projectId(), base.version(), base.defaultPackage(), base.existingTypes(),
            base.existingTables(), ProjectDesignContext.Names.known(Set.of("test.subsystem")), base.iconIds(), base.referenceTargets(), true,
            new RuntimeDesignSnapshot("register-ui", true, Map.of(), facts));
    }
    static EntityDraft draft(String period) {
        return new EntityDraft(3, RecipeCatalog.INDEPENDENT_REGISTER, new UUID(41, 3),
            new EntityProperties("UiRate", DraftText.auto(), "Ставки", EntityProperties.Titles.auto(), DraftText.auto(), null, "FILE"),
            List.of(new FieldDraft(DIM, "region", "Регион", "STRING", DraftText.auto(), true, FieldDraft.UiRequired.AUTO,
                new ConstraintDraft("20", "", ""), null, FieldApplications.root()),
                new FieldDraft(RES, "rate", "Ставка", "DECIMAL", DraftText.auto(), false, FieldDraft.UiRequired.AUTO,
                    new ConstraintDraft("", "19", "2"), null, FieldApplications.root())), List.of(), null,
            new RegisterDraft(period, RegisterDraft.NAMED_AND_CANONICAL_CRUD, List.of(DIM), List.of(RES)));
    }
    static EntityDraftEditorView editor(EntityDraft draft) {
        var v = new EntityDraftEditorView(new EntityDraftAccess(true, new EntityExplorerAccess()));
        v.setContextSource(EntityDraftRegisterUiTest::context); v.initialize(() -> {}); UI.getCurrent().add(v);
        if (draft != null) v.acceptImport(draft, v.documentEpoch(), v.session().revision());
        return v;
    }
    @SuppressWarnings("unchecked") static <T> Select<T> select(EntityDraftEditorView v, String key) {
        return (Select<T>) all(v).filter(c -> c instanceof Select<?> && key.equals(c.getElement().getAttribute("data-draft-input"))).findFirst().orElseThrow();
    }
    DownloadEvent event(ByteArrayOutputStream out) {
        var event = mock(DownloadEvent.class); var ui = mock(UI.class);
        doAnswer(i -> { ((Command)i.getArgument(0)).execute(); return null; }).when(ui).accessSynchronously(any());
        when(event.getUI()).thenReturn(ui); when(event.getResponse()).thenReturn(mock(VaadinResponse.class));
        when(event.getOutputStream()).thenReturn(out); return event;
    }
    @Test void explicitNewKindChoiceOffersImplementedRecipesAndStartsRegisterWithoutTransformingOldInput() {
        var v = editor(null);
        assertThat(button(v, "Показать Java").isEnabled()).isFalse();
        assertThat(v.tabTitle()).isEqualTo("Черновик · Выбор вида");
        button(v, "Новый").click();
        var chooser = all(v).filter(Dialog.class::isInstance).map(Dialog.class::cast).findFirst().orElseThrow();
        assertThat(all(chooser).filter(c -> c instanceof com.vaadin.flow.component.button.Button).map(c -> ((com.vaadin.flow.component.button.Button)c).getText()).toList())
            .contains("Справочник", "Документ", "Регистр сведений", "Простая сущность")
            .doesNotContain("Регистр с регистратором");
        button(chooser, "Регистр сведений").click();
        assertThat(v.session().draft().recipe()).isEqualTo(RecipeCatalog.INDEPENDENT_REGISTER);
        assertThat(v.session().draft().register().period()).isEqualTo("NONE");
        assertThat(v.session().draft().fields()).isEmpty();
        assertThat(button(v, "+ Табчасть").isVisible()).isFalse();
        assertThat(all(v).filter(c -> c instanceof com.vaadin.flow.component.html.Span).map(c -> ((com.vaadin.flow.component.html.Span)c).getText()).toList())
            .anyMatch(s -> s.contains("INDEPENDENT_REGISTER"));
    }
    @Test void rolesUseUuidsAndPeriodSelectionFallsBackWithoutBuiltinNameOrDataLoss() {
        var v = editor(draft("NONE")); clickRow(v, DIM);
        text(v, "name").setValue("renamed");
        assertThat(v.session().draft().register().dimensions()).containsExactly(DIM);
        assertThat(v.session().role(DIM)).isEqualTo("DIMENSION");
        select(v, "register.role").setValue("RESOURCE");
        assertThat(v.session().draft().register().resources()).contains(DIM, RES);
        select(v, "register.period").setValue("DAY");
        v.session().selectBuiltin("builtin:period");
        select(v, "register.period").setValue("NONE");
        assertThat(v.session().place().builtinKey()).isNull();
        assertThat(v.session().draft().fields().getFirst().name()).isEqualTo("renamed");
        v.session().selectField(DIM); v.session().removeSelected(); v.session().selectField(RES); v.session().removeSelected();
        assertThat(v.session().place().fieldId()).isNull();
        assertThat(v.session().draft().register().dimensions()).isEmpty();
        assertThat(v.session().draft().register().resources()).isEmpty();
    }
    @Test void diagnosticsAreVisibleBeforePreviewAndEqualTheFinalGeneratorCodesAndTargets() {
        var d = draft("NONE"); var e = d.entity();
        d = d.withEntity(new EntityProperties(e.className(), e.packageName(), e.label(), e.titles(), e.tableName(), "test.subsystem", e.icon()));
        var v = editor(d);
        assertThat(v.session().generation()).isEmpty();
        assertThat(all(v).filter(c -> "subsystemId".equals(c.getElement().getAttribute("data-generation-target")))).isNotEmpty();
        var early = v.session().readiness().orElseThrow().diagnostics();
        button(v, "Показать Java").click();
        assertThat(v.session().generation().orElseThrow().diagnostics()).isEqualTo(early);
        assertThat(v.session().draft().entity().subsystemId()).isEqualTo("test.subsystem");
    }
    @Test void writeRequiredAndUiRequiredRemainDistinctAndTheConflictAppearsImmediately() {
        var v = editor(draft("DAY")); clickRow(v, DIM);
        EntityDraftRegisterUiTest.<FieldDraft.UiRequired>select(v, "uiRequired").setValue(FieldDraft.UiRequired.OPTIONAL);
        assertThat(v.session().selectedField().serverRequired()).isTrue();
        assertThat(v.session().readiness().orElseThrow().diagnostics()).anyMatch(d -> d.code() == GenerationDiagnostic.Code.UNREPRESENTABLE_REQUIRED_MODE && d.target().fieldId().equals(DIM));
        assertThat(all(v).filter(c -> "uiRequired".equals(c.getElement().getAttribute("data-generation-target")))).isNotEmpty();
        assertThat(v.session().generation()).isEmpty();
        assertThat(all(v).filter(Details.class::isInstance).map(Details.class::cast).filter(d -> d.getSummaryText().equals("Дополнительно · форма")).findFirst().orElseThrow().isOpened()).isFalse();
    }
    @Test void dimensionErrorsAddressTheFieldThatTheUserCanRepair() {
        var v = editor(draft("NONE")); clickRow(v, DIM);
        var required = all(v).filter(c -> c instanceof Checkbox && "serverRequired".equals(c.getElement().getAttribute("data-draft-input")))
            .map(Checkbox.class::cast).findFirst().orElseThrow();
        required.setValue(false);
        assertThat(v.session().validation().orElseThrow().problems()).anyMatch(p -> p.code() == DraftProblem.Code.REGISTER_DIMENSION_REQUIRED
            && DIM.equals(p.target().fieldId()) && "serverRequired".equals(p.target().propertyKey()));
        assertThat(required.isInvalid()).isTrue();
        assertThat(v.session().draft().register().dimensions()).containsExactly(DIM);
    }
    @Test void advancedAutoExplicitValuesAndAccessibleIndicatorsSurviveNavigation() {
        var v = editor(draft("NONE"));
        var extra = all(v).filter(Details.class::isInstance).map(Details.class::cast).filter(d -> d.getSummaryText().equals("Дополнительно")).findFirst().orElseThrow();
        assertThat(extra.isOpened()).isFalse(); extra.setOpened(true);
        assertThat(v.session().draft().entity().tableName().mode()).isEqualTo(DraftText.Mode.AUTO);
        text(v, "tableName").setValue("explicit_rate");
        button(v, "Данные").click(); clickRow(v, RES); button(v, "Основные").click();
        assertThat(text(v, "tableName").getValue()).isEqualTo("explicit_rate");
        assertThat(v.session().draft().entity().tableName().mode()).isEqualTo(DraftText.Mode.EXPLICIT);
        assertThat(all(v).filter(c -> c.hasClassName("draft-field-indicators")).map(c -> c.getElement().getAttribute("aria-label")).toList())
            .anyMatch(s -> s.contains("Измерение") && s.contains("Запись: обязательно") && s.contains("Форма +"))
            .anyMatch(s -> s.contains("Ресурс") && s.contains("Таблица −"));
    }
    @Test void v3JsonAndActualJavaDownloadAgreeWithPreviewAndChangesInvalidateDelivery() throws Exception {
        var codec = new DraftJsonCodec();
        for (var period : List.of("NONE", "DAY")) {
            var d = draft(period); var v = editor(codec.decode(codec.encode(d)));
            assertThat(v.prepareExport().fileName()).isEqualTo("UiRate.json");
            assertThat(codec.decode(v.prepareExport().bytes())).isEqualTo(d);
            button(v, "Показать Java").click(); var token = v.javaExport(); assertThat(token).isNotNull();
            var bytes = new ByteArrayOutputStream(); v.javaDownloadHandler(token).handleDownloadRequest(event(bytes));
            assertThat(bytes.toByteArray()).isEqualTo(token.plan().files().getFirst().content().getBytes(StandardCharsets.UTF_8));
            var jsonBytes = new ByteArrayOutputStream(); v.downloadHandler(v.prepareExport()).handleDownloadRequest(event(jsonBytes));
            assertThat(codec.decode(jsonBytes.toByteArray())).isEqualTo(d);
            String output = System.getProperty("design.g1r4.output");
            if (output != null) {
                var directory = java.nio.file.Path.of(output).resolve(period.toLowerCase(java.util.Locale.ROOT));
                java.nio.file.Files.createDirectories(directory);
                java.nio.file.Files.write(directory.resolve("download.java"), bytes.toByteArray());
                java.nio.file.Files.write(directory.resolve("download.json"), jsonBytes.toByteArray());
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter();
                java.nio.file.Files.writeString(directory.resolve("plan.json"), mapper.writeValueAsString(token.plan()));
                java.nio.file.Files.writeString(directory.resolve("manifest.json"), mapper.writeValueAsString(GenerationManifest.from(token.plan(), EntityJavaGenerator.fingerprintProfile(token.plan().recipe()))));
                java.nio.file.Files.writeString(directory.resolve("context.json"), mapper.writeValueAsString(v.session().context()));
                java.nio.file.Files.writeString(directory.resolve("origin.txt"), "Actual P8 component handlers; not browser/HTTP or C/D acceptance.\n");
            }
            select(v, "register.period").setValue(period.equals("NONE") ? "DAY" : "NONE");
            var refused = event(new ByteArrayOutputStream()); v.javaDownloadHandler(token).handleDownloadRequest(refused);
            verify(refused.getResponse()).setStatus(409);
            assertThat(v.session().generation()).isEmpty();
        }
    }
    @Test void jsonNameFallbackDoesNotChangeCanonicalContentsAndDocumentHasNoEmptyOwnerPanel() {
        var v = editor(draft("NONE")); text(v, "className").setValue("Unfinished-");
        assertThat(v.prepareExport().fileName()).isEqualTo("entity-draft-" + v.session().draft().draftId() + ".json");
        assertThat(new DraftJsonCodec().decode(v.prepareExport().bytes())).isEqualTo(v.session().draft());
        var doc = new DraftOperations().create(RecipeCatalog.STANDARD_DOCUMENT);
        var document = editor(doc);
        assertThat(all(document).filter(c -> c instanceof com.vaadin.flow.component.html.Span)
            .map(c -> ((com.vaadin.flow.component.html.Span)c).getText()).toList())
            .doesNotContain("Владелец для документа не поддерживается");
    }
    @Test void importedUnsupportedOwnerAndSectionsStayAvailableForDiagnosisWithoutChangingRawData() {
        var d = draft("NONE");
        var raw = d.withOwner(new OwnerDraft("unknown.Owner", "owner", DraftText.auto(), "Владелец"))
            .withSections(List.of(new SectionDraft(new UUID(41, 4), "Строки", "UiRateRow", DraftText.auto(), DraftText.auto(), DraftText.auto(), DraftText.auto(), List.of())));
        var v = editor(raw);
        assertThat(v.session().draft()).isEqualTo(raw);
        assertThat(v.session().validation().orElseThrow().hasErrors()).isTrue();
        button(v, "Данные").click();
        assertThat(all(v).filter(c -> raw.sections().getFirst().id().toString().equals(c.getElement().getAttribute("data-section-id")))).isNotEmpty();
        assertThat(v.session().draft().owner()).isEqualTo(raw.owner());
    }
    @Test void contextRefreshInvalidatesRegisterPreviewWithoutTouchingRawInput() throws Exception {
        var v = editor(draft("DAY")); button(v, "Показать Java").click(); var token = v.javaExport();
        var original = v.session().draft();
        var facts = new HashMap<>(context().runtimeSnapshot().prerequisites());
        facts.put("REGISTER_ENGINE_OPERATIONS", new RuntimeDesignSnapshot.Prerequisite(RuntimeDesignSnapshot.Status.UNAVAILABLE, "not installed"));
        v.setContextSource(() -> context().withRuntimeSnapshot(new RuntimeDesignSnapshot("changed", true, Map.of(), facts)));
        button(v, "Обновить каталог").click();
        assertThat(v.session().draft()).isSameAs(original);
        assertThat(v.session().readiness().orElseThrow().ready()).isFalse();
        assertThat(v.getElement().getTextRecursively()).contains("Не подтверждена доступность операций регистра");
        assertThat(v.javaExport()).isNull();
        var refused = event(new ByteArrayOutputStream()); v.javaDownloadHandler(token).handleDownloadRequest(refused);
        verify(refused.getResponse()).setStatus(409);
    }
    @Test void referenceDimensionUsesTheSameV3CodecAndActualGeneratorWithCurrentTargetFacts() {
        var d = draft("DAY"); var fields = new ArrayList<>(d.fields());
        fields.set(0, new FieldDraft(DIM, "item", "Номенклатура", "REFERENCE", DraftText.auto(), true,
            FieldDraft.UiRequired.AUTO, ConstraintDraft.empty(), new ReferenceDraft("com.example.Item"), FieldApplications.root()));
        d = d.withFields(fields);
        var v = editor(new DraftJsonCodec().decode(new DraftJsonCodec().encode(d)));
        var base = context();
        var target = new ReferenceTargetDescriptor("com.example.Item", "Item", "STANDARD_ROOT", "java.lang.Long",
            ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Fact.YES, ReferenceTargetDescriptor.Eligibility.SUPPORTED, "");
        var references = new ProjectDesignContext(base.projectId(), base.version(), base.defaultPackage(), base.existingTypes(),
            base.existingTables(), base.subsystemIds(), base.iconIds(), Map.of(target.typeId(), target), true, base.runtimeSnapshot());
        v.setContextSource(() -> references); v.activate(); clickRow(v, DIM);
        assertThat(v.session().readiness().orElseThrow().ready()).isTrue();
        button(v, "Показать Java").click();
        assertThat(v.javaExport().plan().files().getFirst().content()).contains("private com.example.Item item;", "@JoinColumn", "period_date").doesNotContain("CascadeType");
    }
    @Test void choosingAnotherKindNeverConvertsOrDiscardsDirtyRegisterInputWithoutConfirmation() {
        var v = editor(draft("NONE")); text(v, "label").setValue("Сохранить мой ввод");
        var original = v.session().draft();
        button(v, "Новый").click();
        var chooser = all(v).filter(Dialog.class::isInstance).map(Dialog.class::cast).findFirst().orElseThrow();
        button(chooser, "Документ").click();
        var confirm = all(v).filter(com.vaadin.flow.component.confirmdialog.ConfirmDialog.class::isInstance)
            .map(com.vaadin.flow.component.confirmdialog.ConfirmDialog.class::cast).findFirst().orElseThrow();
        assertThat(v.session().draft()).isSameAs(original); confirm.close();
        assertThat(v.session().draft()).isSameAs(original);
        assertThat(v.session().draft().register().dimensions()).containsExactly(DIM);
    }
}
