package org.ip.views.admin;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.design.*;
import org.ipro.form.Savable;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;

class EntityDraftEditorViewTest {
    static com.vaadin.flow.component.UI uiWithResources() {
        var ui = new com.vaadin.flow.component.UI();
        var session = org.mockito.Mockito.mock(com.vaadin.flow.server.VaadinSession.class);
        org.mockito.Mockito.when(session.hasLock()).thenReturn(true);
        org.mockito.Mockito.when(session.getLocale()).thenReturn(Locale.forLanguageTag("ru"));
        org.mockito.Mockito.when(session.getResourceRegistry()).thenReturn(new com.vaadin.flow.server.StreamResourceRegistry(session));
        ui.getInternals().setSession(session);
        return ui;
    }
    static void principal(String name, boolean admin) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, "", admin ? List.of(new SimpleGrantedAuthority("ROLE_ADMIN")) : List.of()));
    }
    @BeforeEach void login() { principal("admin", true); }
    @AfterEach void logout() { com.vaadin.flow.component.UI.setCurrent(null); SecurityContextHolder.clearContext(); }
    static Stream<Component> all(Component c) { return Stream.concat(Stream.of(c), c.getChildren().flatMap(EntityDraftEditorViewTest::all)); }
    static TextField text(EntityDraftEditorView view, String key) {
        return all(view).filter(c -> c instanceof TextField && key.equals(c.getElement().getAttribute("data-draft-input"))).map(c -> (TextField)c).findFirst().orElseThrow();
    }
    static Button button(Component view, String caption) {
        return all(view).filter(c -> c instanceof Button b && b.getText().equals(caption)).map(c -> (Button)c).findFirst().orElseThrow();
    }
    static void clickRow(EntityDraftEditorView view, UUID id) {
        var row = all(view).filter(c -> c instanceof NativeButton && id.toString().equals(c.getElement().getAttribute("data-field-id"))).findFirst().orElseThrow();
        com.vaadin.flow.component.ComponentUtil.fireEvent(row, new com.vaadin.flow.component.ClickEvent<>(row));
    }
    static EntityDraftEditorView view() {
        var view = new EntityDraftEditorView(new EntityDraftAccess(true, new EntityExplorerAccess()));
        view.setContextSource(() -> DraftEditorSessionTest.context("v1")); view.initialize(() -> {}); button(view, "Справочник").click(); return view;
    }
    @Test void actualEditorKeepsIncompleteInputAcrossPropertyAndPageSelection() {
        var view = view(); assertThat(view).isNotInstanceOf(Savable.class);
        text(view, "className").setValue("Partial-"); text(view, "label").setValue("Материалы");
        assertThat(text(view, "tableName").getValue()).isEqualTo("partial-");
        text(view, "tableName").setValue("fixed_table"); text(view, "className").setValue("Renamed");
        assertThat(text(view, "tableName").getValue()).isEqualTo("fixed_table");
        button(view, "Данные").click(); button(view, "+ Реквизит").click(); UUID first = view.session().place().fieldId();
        text(view, "name").setValue("weight"); text(view, "field.label").setValue("Масса");
        text(view, "constraints.length").setValue("12x");
        button(view, "+ Реквизит").click(); text(view, "name").setValue("note");
        clickRow(view, first);
        assertThat(text(view, "constraints.length").getValue()).isEqualTo("12x");
        button(view, "Основные").click(); button(view, "Данные").click(); view.activate();
        assertThat(view.session().selectedField().name()).isEqualTo("weight");
        assertThat(text(view, "constraints.length").getValue()).isEqualTo("12x");
        assertThat(view.tabTitle()).isEqualTo("Черновик · Материалы *");
    }
    @Test void plainEditorProducesCompilableJavaAndExecutableDdl(@org.junit.jupiter.api.io.TempDir java.nio.file.Path output)
            throws Exception {
        var ui = uiWithResources();
        com.vaadin.flow.component.UI.setCurrent(ui);
        var view = new EntityDraftEditorView(new EntityDraftAccess(true, new EntityExplorerAccess()));
        view.setContextSource(() -> DraftEditorSessionTest.context("v1"));
        view.initialize(() -> {});
        ui.add(view);
        button(view, "Простая сущность").click();
        text(view, "className").setValue("RejectionReason");
        text(view, "label").setValue("Причина отказа");
        button(view, "Данные").click();
        button(view, "+ Реквизит").click();
        text(view, "name").setValue("caption");
        text(view, "field.label").setValue("Подпись");
        button(view, "Назначить именем экземпляра").click();
        assertThat(view.session().draft().presentationFieldId()).isEqualTo(view.session().selectedField().id());
        assertThat(view.session().selectedField().serverRequired()).isTrue();
        text(view, "name").setValue("reasonText");
        button(view, "Показать Java и DDL").click();

        var plan = view.javaExport().plan();
        assertThat(plan.files()).extracting(org.ipro.design.generation.GenerationFile::relativePath)
            .containsExactly("org/example/RejectionReason.java", "org/example/RejectionReasonFormConfiguration.java",
                "schema/RejectionReason.sql");
        assertThat(plan.files().getFirst().content()).contains("@InstanceName(\"reasonText\")");
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(JavaBundleArchive.bytes(plan)))) {
            var entries = new ArrayList<String>();
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) entries.add(entry.getName());
            assertThat(entries).contains("schema/RejectionReason.sql", JavaBundleArchive.MANIFEST_ENTRY);
        }
        compilePlainJava(plan, output);
        try (var connection = java.sql.DriverManager.getConnection("jdbc:h2:mem:plain_editor_contract");
             var statement = connection.createStatement()) {
            statement.execute(plan.files().getLast().content());
            assertThat(statement.executeUpdate("INSERT INTO rejection_reason (reason_text) VALUES ('Проверка')")).isEqualTo(1);
            org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                () -> statement.executeUpdate("INSERT INTO rejection_reason (reason_text) VALUES (NULL)"));
        }
    }

    @Test void plainDdlMatchesHibernateForEveryScalarAndInheritedColumn(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path output) throws Exception {
        var context = DraftEditorSessionTest.context("v1");
        var ops = new DraftOperations();
        var draft = ops.create(RecipeCatalog.CUSTOM_PLAIN_V2).withEntity(new EntityProperties(
            "ScalarReason", DraftText.explicit("org.example"), "Причина отказа", EntityProperties.Titles.auto(),
            DraftText.explicit("scalar_reason"), null, "FILE"));
        var fields = List.of(
            scalar("caption", "STRING", true, new ConstraintDraft("120", "", "")),
            scalar("itemCount", "INTEGER", false, ConstraintDraft.empty()),
            scalar("externalId", "LONG", true, ConstraintDraft.empty()),
            scalar("amount", "DECIMAL", false, new ConstraintDraft("", "12", "3")),
            scalar("wholeAmount", "DECIMAL", false, new ConstraintDraft("", "14", "0")),
            scalar("active", "BOOLEAN", false, ConstraintDraft.empty()),
            scalar("effectiveDate", "DATE", false, ConstraintDraft.empty()),
            scalar("receivedAt", "DATETIME", false, ConstraintDraft.empty()));
        draft = draft.withFields(fields).withPresentationField(fields.getFirst().id());
        var checked = new DraftValidator().validate(draft, context).checkedStructure().orElseThrow();
        var plan = new org.ipro.design.generation.EntityJavaGenerator().generate(checked, draft, context).plan().orElseThrow();
        compilePlainJava(plan, output);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String database = "jdbc:h2:mem:plain_mapping_" + suffix + ";MODE=PostgreSQL";
        String ddlDatabase = "jdbc:h2:mem:plain_ddl_" + suffix + ";MODE=PostgreSQL";
        Integer postgresPort = Integer.getInteger("plain.acceptance.postgresPort");
        if (postgresPort != null) {
            String base = "jdbc:postgresql://127.0.0.1:" + postgresPort + "/postgres?user=plain_stage4";
            String mappingSchema = "plain_mapping_" + suffix;
            String ddlSchema = "plain_ddl_" + suffix;
            try (var connection = java.sql.DriverManager.getConnection(base); var statement = connection.createStatement()) {
                statement.execute("CREATE SCHEMA " + mappingSchema);
                statement.execute("CREATE SCHEMA " + ddlSchema);
            }
            database = base + "&currentSchema=" + mappingSchema;
            ddlDatabase = base + "&currentSchema=" + ddlSchema;
        }
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, getClass().getClassLoader());
             var mapping = java.sql.DriverManager.getConnection(database)) {
            Class<?> entity = loader.loadClass("org.example.ScalarReason");
            var bootstrap = new org.hibernate.boot.registry.BootstrapServiceRegistryBuilder()
                .applyClassLoader(loader).build();
            var registry = new org.hibernate.boot.registry.StandardServiceRegistryBuilder(bootstrap)
                .applySetting("hibernate.connection.url", database)
                .applySetting("hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy")
                .applySetting("hibernate.hbm2ddl.auto", "create")
                .build();
            try (var factory = new org.hibernate.boot.MetadataSources(registry)
                    .addAnnotatedClass(entity).buildMetadata().buildSessionFactory();
                 var ddl = java.sql.DriverManager.getConnection(ddlDatabase);
                 var statement = ddl.createStatement();
                 var validatorFactory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
                statement.execute(plan.files().getLast().content());
                assertThat(columns(ddl)).isEqualTo(columns(mapping));
                assertThat(primaryKeys(ddl)).containsExactly("id").isEqualTo(primaryKeys(mapping));
                Object row = entity.getConstructor().newInstance();
                entity.getMethod("setCaption", String.class).invoke(row, "   ");
                entity.getMethod("setExternalId", Long.class).invoke(row, 42L);
                assertThat(validatorFactory.getValidator().validate(row)).anyMatch(violation ->
                    violation.getPropertyPath().toString().equals("caption")
                        && violation.getConstraintDescriptor().getAnnotation() instanceof jakarta.validation.constraints.NotBlank);
                entity.getMethod("setCaption", String.class).invoke(row, "Проверка");
                assertThat(validatorFactory.getValidator().validate(row)).isEmpty();
                verifyWrites(factory, ddl, entity, row);
            } finally {
                org.hibernate.boot.registry.StandardServiceRegistryBuilder.destroy(registry);
            }
        }
    }

    private static void verifyWrites(org.hibernate.SessionFactory factory, java.sql.Connection ddl, Class<?> entity,
                                     Object row) throws ReflectiveOperationException {
        try (var session = factory.withOptions().connection(ddl).openSession()) {
            var transaction = session.beginTransaction();
            session.persist(row);
            session.flush();
            var id = entity.getMethod("getId").invoke(row);
            assertThat(id).isNotNull();
            long originalVersion = (long) entity.getMethod("getVersion").invoke(row);
            session.clear();

            var stored = session.find(entity, id);
            assertThat(entity.getMethod("getCaption").invoke(stored)).isEqualTo("Проверка");
            entity.getMethod("setCaption", String.class).invoke(stored, "Изменено");
            session.flush();
            assertThat(entity.getMethod("getVersion").invoke(stored)).isEqualTo(originalVersion + 1);
            session.clear();

            var updated = session.find(entity, id);
            assertThat(entity.getMethod("getCaption").invoke(updated)).isEqualTo("Изменено");
            session.remove(updated);
            session.flush();
            assertThat(session.find(entity, id)).isNull();
            transaction.commit();
        }
    }

    private static FieldDraft scalar(String name, String type, boolean required, ConstraintDraft constraints) {
        return new FieldDraft(UUID.randomUUID(), name, name, type, DraftText.auto(), required, FieldDraft.UiRequired.AUTO,
            constraints, null, FieldApplications.root());
    }

    private record SqlColumn(int jdbcType, int size, int scale, int nullable, String identity) {}

    private static Map<String, SqlColumn> columns(java.sql.Connection connection) throws java.sql.SQLException {
        var columns = new LinkedHashMap<String, SqlColumn>();
        var metadata = connection.getMetaData();
        String table = metadata.storesUpperCaseIdentifiers() ? "SCALAR_REASON" : "scalar_reason";
        try (var rows = metadata.getColumns(null, connection.getSchema(), table, null)) {
            while (rows.next()) {
                int type = rows.getInt("DATA_TYPE");
                int size = switch (type) {
                    case java.sql.Types.VARCHAR, java.sql.Types.DECIMAL, java.sql.Types.NUMERIC -> rows.getInt("COLUMN_SIZE");
                    default -> 0;
                };
                if (type == java.sql.Types.DECIMAL) type = java.sql.Types.NUMERIC;
                columns.put(rows.getString("COLUMN_NAME"), new SqlColumn(type, size,
                    rows.getInt("DECIMAL_DIGITS"), rows.getInt("NULLABLE"), rows.getString("IS_AUTOINCREMENT")));
            }
        }
        return columns;
    }

    private static List<String> primaryKeys(java.sql.Connection connection) throws java.sql.SQLException {
        var names = new ArrayList<String>();
        var metadata = connection.getMetaData();
        String table = metadata.storesUpperCaseIdentifiers() ? "SCALAR_REASON" : "scalar_reason";
        try (var rows = metadata.getPrimaryKeys(null, connection.getSchema(), table)) {
            while (rows.next()) names.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static void compilePlainJava(org.ipro.design.generation.GenerationPlan plan, java.nio.file.Path output)
            throws Exception {
        var javac = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none",
            "-cp", System.getProperty("java.class.path"), "-d", output.toString()));
        for (var file : plan.files().subList(0, 2)) {
            var source = output.resolve(file.relativePath());
            java.nio.file.Files.createDirectories(source.getParent());
            java.nio.file.Files.writeString(source, file.content());
            javac.add(source.toString());
        }
        var diagnostics = new java.io.ByteArrayOutputStream();
        int exitCode = javax.tools.ToolProvider.getSystemJavaCompiler()
            .run(null, null, diagnostics, javac.toArray(String[]::new));
        assertThat(exitCode).as(diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8)).isZero();
    }
    @Test void typeSwitchShowsConflictingInputAndExplicitCleanupInsteadOfDiscardingIt() {
        var view = view(); button(view, "+ Реквизит").click();
        @SuppressWarnings("unchecked") Select<String> type = (Select<String>) all(view).filter(c -> c instanceof Select<?> && "type".equals(c.getElement().getAttribute("data-draft-input"))).findFirst().orElseThrow();
        type.setValue("DECIMAL");
        assertThat(text(view, "constraints.length").getValue()).isEqualTo("255");
        assertThat(text(view, "constraints.length").isInvalid()).isTrue();
        button(view, "Очистить").click();
        assertThat(view.session().selectedField().constraints().length()).isEmpty();
        assertThat(all(view).filter(c -> "constraints.length".equals(c.getElement().getAttribute("data-draft-input")))) .isEmpty();
    }
    @Test void inheritedRowsExposeNoEditingOrRemovalAndNewStartsClean() {
        var ui = uiWithResources(); com.vaadin.flow.component.UI.setCurrent(ui);
        var view = view(); ui.add(view); button(view, "Данные").click();
        assertThat(view.session().draft().recipe()).isEqualTo(RecipeCatalog.STANDARD_CATALOG_V2);
        var row = all(view).filter(c -> "builtin:code".equals(c.getElement().getAttribute("data-builtin"))).findFirst().orElseThrow();
        com.vaadin.flow.component.ComponentUtil.fireEvent(row, new com.vaadin.flow.component.ClickEvent<>(row));
        assertThat(button(view, "Удалить").isEnabled()).isFalse();
        assertThat(view.session().draft().fields()).isEmpty(); assertThat(view.isDirty()).isFalse();
        UUID original = view.session().draft().draftId(); button(view, "Новый").click();
        var chooser = all(view).filter(c -> c instanceof com.vaadin.flow.component.dialog.Dialog).findFirst().orElseThrow();
        button(chooser, "Справочник").click();
        assertThat(view.session().draft().draftId()).isNotEqualTo(original); assertThat(view.isDirty()).isFalse();
        assertThat(view.session().draft().recipe()).isEqualTo(RecipeCatalog.STANDARD_CATALOG_V2);
    }
    @Test void v2EditorKeepsUnknownRuntimeFactsBlockingAfterCatalogRefresh() {
        var view = view(); text(view, "className").setValue("MaterialCard"); text(view, "label").setValue("Материалы");
        button(view, "Проверить").click();
        assertThat(view.session().validation().orElseThrow().checkedStructure()).isPresent();
        var before = view.session().draft(); long revision = view.session().revision();
        view.setContextSource(() -> DraftEditorSessionTest.context("unknown").withRuntimeSnapshot(RuntimeDesignSnapshot.unknown()));
        button(view, "Обновить каталог").click();
        assertThat(view.session().validation().orElseThrow().checkedStructure()).isEmpty();
        assertThat(view.session().draft()).isSameAs(before);
        assertThat(view.session().revision()).isEqualTo(revision);
        assertThat(view.isDirty()).isTrue();
    }
    @Test void principalOrAccessChangeErasesOldInputButContextRefreshDoesNot() {
        var view = view(); text(view, "label").setValue("Секретный черновик"); UUID id = view.session().draft().draftId();
        view.setContextSource(() -> DraftEditorSessionTest.context("v2")); view.activate();
        assertThat(view.session().draft().draftId()).isEqualTo(id); assertThat(view.isDirty()).isTrue();
        principal("another-admin", true); view.activate();
        assertThat(view.session().draft().draftId()).isNotEqualTo(id); assertThat(text(view, "label").getValue()).isEmpty();
        button(view, "Справочник").click();
        text(view, "label").setValue("Не показывать"); principal("user", false); view.activate();
        assertThat(view.session()).isNull(); assertThat(view.isDirty()).isFalse(); assertThat(all(view).filter(c -> c instanceof TextField)).isEmpty();
    }
    @Test void commandsRecheckAuthorizationAndDisabledPilotNeverCreatesADraft() {
        var view = view(); var add = button(view, "+ Реквизит"); principal("user", false); add.click();
        assertThat(view.session()).isNull();
        principal("admin", true);
        var disabled = new EntityDraftEditorView(new EntityDraftAccess(false, new EntityExplorerAccess())); disabled.initialize(() -> {});
        assertThat(disabled.session()).isNull(); assertThat(all(disabled).filter(c -> c instanceof TextField)).isEmpty();
    }
    @Test void twoActualViewsHaveIndependentDraftsAndInputs() {
        var first = view(); var second = view(); text(first, "label").setValue("Первый"); button(first, "+ Реквизит").click();
        assertThat(text(second, "label").getValue()).isEmpty(); assertThat(second.session().draft().fields()).isEmpty(); assertThat(second.isDirty()).isFalse();
    }
    @Test void explicitAutoResetAndLiveFieldCaptionFollowTheSameDraft() {
        var view = view(); text(view, "className").setValue("FirstCard");
        text(view, "tableName").setValue("fixed_table"); text(view, "className").setValue("SecondCard");
        assertThat(text(view, "tableName").getValue()).isEqualTo("fixed_table");
        var reset = all(view).filter(c -> c instanceof Button && "tableName".equals(c.getElement().getAttribute("data-draft-reset"))).map(c -> (Button)c).findFirst().orElseThrow();
        reset.click(); assertThat(view.session().draft().entity().tableName()).isEqualTo(DraftText.auto());
        assertThat(text(view, "tableName").getValue()).isEqualTo("second_card");
        button(view, "+ Реквизит").click(); text(view, "name").setValue("newName");
        assertThat(all(view).filter(c -> c instanceof com.vaadin.flow.component.html.H4 h && h.getText().equals("Справочник / newName"))).hasSize(1);
    }
    @Test void lateInputFromThePreviousDocumentCannotWriteIntoTheNewOwnersDraft() {
        var view = view(); var previousLabel = text(view, "label");
        previousLabel.setValue("Старый ввод"); principal("other-admin", true); view.activate();
        UUID newId = view.session().draft().draftId(); previousLabel.setValue("Запоздалое событие");
        assertThat(view.session().draft().draftId()).isEqualTo(newId);
        assertThat(view.session().draft().entity().label()).isEmpty(); assertThat(view.isDirty()).isFalse();
    }
}
