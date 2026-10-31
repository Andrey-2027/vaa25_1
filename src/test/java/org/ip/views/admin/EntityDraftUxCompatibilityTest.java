package org.ip.views.admin;

import com.vaadin.flow.component.UI;
import org.ipro.design.*;
import org.ipro.design.json.DraftJsonCodec;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.ip.views.admin.EntityDraftEditorViewTest.*;

class EntityDraftUxCompatibilityTest {
    @BeforeEach void login() { principal("admin", true); }
    @AfterEach void logout() { UI.setCurrent(null); org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @Test void sameJsonInTwoUiContextsHasIndependentInputNavigationAndCheckpoints() {
        var codec = new DraftJsonCodec(); var original = new DraftOperations().create();
        byte[] bytes = codec.encode(original);
        UI firstUi = new UI(); UI.setCurrent(firstUi); var first = view(); firstUi.add(first);
        first.acceptImport(codec.decode(bytes), first.documentEpoch(), first.session().revision());
        UI secondUi = new UI(); UI.setCurrent(secondUi); var second = view(); secondUi.add(second);
        second.acceptImport(codec.decode(bytes), second.documentEpoch(), second.session().revision());
        assertThat(first.session().draft()).isEqualTo(second.session().draft()).isNotSameAs(second.session().draft());
        UI.setCurrent(firstUi); text(first, "label").setValue("Только первый UI"); button(first, "+ Табчасть").click();
        text(first, "section.label").setValue("Только первая секция"); button(first, "+ Реквизит").click();
        text(first, "constraints.length").setValue("12x"); var export = first.prepareExport();
        assertThat(first.isDirty()).isTrue(); assertThat(second.isDirty()).isFalse();
        assertThat(second.session().draft()).isEqualTo(original); assertThat(second.session().place().sectionId()).isNull();
        first.exportDelivered(export); assertThat(first.isDirty()).isFalse();
        UI.setCurrent(secondUi); text(second, "className").setValue("SecondOnly");
        assertThat(first.session().draft().entity().className()).isEmpty();
        assertThat(first.session().draft().sections().getFirst().fields().getFirst().constraints().length()).isEqualTo("12x");
    }

    @Test void localeAndCatalogChangesPreserveRawInputIdsAndSelection() {
        UI ui = new UI(); UI.setCurrent(ui); var v = view(); ui.add(v);
        text(v, "className").setValue("Partial-"); button(v, "+ Табчасть").click();
        text(v, "section.label").setValue("Длинная подпись табчасти"); button(v, "+ Реквизит").click();
        text(v, "name").setValue("incomplete-"); text(v, "constraints.length").setValue("12x");
        var draft = v.session().draft(); var place = v.session().place(); long revision = v.session().revision();
        ui.setLocale(Locale.ENGLISH); v.activate();
        var base = DraftEditorSessionTest.context("changed");
        v.setContextSource(() -> new ProjectDesignContext(base.projectId(), "unknown-tables", base.defaultPackage(),
            base.existingTypes(), ProjectDesignContext.Names.unknown("metadata temporarily unavailable"),
            base.subsystemIds(), base.iconIds(), base.referenceTargets(), base.referencesComplete()));
        v.activate();
        assertThat(v.session().draft()).isSameAs(draft); assertThat(v.session().place()).isEqualTo(place);
        assertThat(v.session().revision()).isEqualTo(revision); assertThat(v.isDirty()).isTrue();
        assertThat(text(v, "name").getValue()).isEqualTo("incomplete-");
        assertThat(text(v, "constraints.length").getValue()).isEqualTo("12x");
        assertThat(v.session().validation().orElseThrow().checkedStructure()).isEmpty();
        assertThat(v.session().validation().orElseThrow().problems()).anyMatch(p -> p.code() == DraftProblem.Code.PROJECT_FACT_UNKNOWN);
    }
}
