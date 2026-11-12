package org.ip.views.admin;

import com.vaadin.flow.component.UI;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.SubsystemRegistry;
import org.ipro.vaadin.explorer.EntitySummaryAssembler;
import org.ipro.vaadin.explorer.ExplorerSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EntityExplorerCatalogTest {
    private final EntityExplorerAccess access = new EntityExplorerAccess();

    @BeforeEach void login() { principal("admin", "ROLE_ADMIN"); }
    @AfterEach void cleanup() { UI.setCurrent(null); SecurityContextHolder.clearContext(); }

    private static void principal(String name, String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, "",
            java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList()));
    }

    private static EntityExplorerCatalog.Facts facts() {
        return new EntityExplorerCatalog.Facts(EntityDraftCatalogAdapterTest.snapshot(List.of(), Map.of()), List.of());
    }

    @Test void productionAssemblerBuildsOneSnapshotUntilExplicitRefresh() {
        var assembler = mock(EntitySummaryAssembler.class);
        var first = mock(ExplorerSnapshot.class);
        var second = mock(ExplorerSnapshot.class);
        when(assembler.explorerSnapshot()).thenReturn(first, second);
        var catalog = new EntityExplorerCatalog(assembler, access);
        var revision = catalog.revision();
        verifyNoInteractions(assembler);
        var cached = catalog.current();
        assertThat(catalog.current()).isSameAs(cached);
        verify(assembler, times(1)).explorerSnapshot();
        catalog.refresh();
        assertThat(catalog.revision().value()).isGreaterThan(revision.value());
        assertThat(catalog.revision().context()).isEqualTo(revision.context());
        verify(assembler, times(1)).explorerSnapshot();
        assertThat(catalog.current()).isNotSameAs(cached);
        verify(assembler, times(2)).explorerSnapshot();
    }

    @Test void accessIsCheckedBeforeAssemblyAndRefresh() {
        var reads = new AtomicInteger();
        var catalog = new EntityExplorerCatalog(() -> { reads.incrementAndGet(); return facts(); }, access);
        principal("user", "ROLE_USER");
        assertThatThrownBy(catalog::current).isInstanceOf(SecurityException.class);
        assertThatThrownBy(catalog::refresh).isInstanceOf(SecurityException.class);
        assertThat(reads).hasValue(0);
        principal("admin", "ROLE_ADMIN");
        catalog.current();
        assertThat(reads).hasValue(1);
    }

    @Test void principalRolesAndLocaleInvalidateSharedFactsButRoleOrderDoesNot() {
        UI ui = new UI(); ui.setLocale(Locale.forLanguageTag("ru")); UI.setCurrent(ui);
        var catalog = new EntityExplorerCatalog(EntityExplorerCatalogTest::facts, access);
        principal("admin", "ROLE_ADMIN", "ROLE_USER");
        var first = catalog.current();
        var revision = catalog.revision();
        principal("admin", "ROLE_USER", "ROLE_ADMIN");
        assertThat(catalog.current()).isSameAs(first);
        assertThat(catalog.revision()).isEqualTo(revision);
        principal("admin", "ROLE_ADMIN");
        var roles = catalog.current();
        assertThat(roles).isNotSameAs(first);
        principal("another-admin", "ROLE_ADMIN");
        var owner = catalog.current();
        assertThat(owner).isNotSameAs(roles);
        ui.setLocale(Locale.ENGLISH);
        assertThat(catalog.current()).isNotSameAs(owner);
        assertThat(catalog.revision().context()).isEqualTo(revision.context() + 3);
    }

    @Test void independentUiCatalogsDoNotShareFactsOrRevisions() {
        var first = new EntityExplorerCatalog(EntityExplorerCatalogTest::facts, access);
        var second = new EntityExplorerCatalog(EntityExplorerCatalogTest::facts, access);
        var cached = second.current();
        var revision = second.revision();
        assertThat(first.current()).isNotSameAs(cached);
        first.refresh();
        assertThat(second.current()).isSameAs(cached);
        assertThat(second.revision()).isEqualTo(revision);
    }

    @Test void navigationAndDraftShareFactsWithoutCreatingTheExplorerAndRefreshKeepsDirtyInput() {
        var reads = new AtomicInteger();
        var entry = EntityDraftCatalogAdapterTest.entry(EntityDraftCatalogAdapterTest.Root.class,
            org.ipro.data.EntityExposure.STANDARD_ROOT);
        var view = EntityDraftCatalogAdapterTest.snapshot(List.of(entry),
            Map.of(entry.type(), EntityDraftCatalogAdapterTest.summary(true, true)));
        var catalog = new EntityExplorerCatalog(() -> {
            reads.incrementAndGet(); return new EntityExplorerCatalog.Facts(view, List.of());
        }, access);
        var navigation = new EntityExplorerNavigation(access, mock(FormRouteCatalog.class), catalog);
        var managed = mock(ManagedEntityCatalog.class);
        when(managed.managedEntityClasses()).thenReturn(Set.of(entry.type()));
        var subsystems = mock(SubsystemRegistry.class);
        var project = new EntityDraftProjectCatalog(catalog, managed, subsystems,
            new EntityDraftAccess(true, access), "org.example", mock(ObjectProvider.class));
        assertThat(navigation.availability(entry.type()).available()).isTrue();
        project.current();
        assertThat(reads).hasValue(1);

        var editor = EntityDraftEditorViewTest.view();
        editor.setCatalogSource(project::current);
        editor.setCatalogRefresh(project::refresh);
        EntityDraftEditorViewTest.text(editor, "label").setValue("Незавершённый ввод");
        var draft = editor.session().draft();
        EntityDraftEditorViewTest.button(editor, "Обновить каталог").click();
        assertThat(reads).hasValue(2);
        assertThat(editor.session().draft()).isSameAs(draft);
        assertThat(editor.isDirty()).isTrue();
        assertThat(EntityDraftEditorViewTest.text(editor, "label").getValue()).isEqualTo("Незавершённый ввод");
        assertThat(navigation.availability(entry.type()).available()).isTrue();
        assertThat(reads).hasValue(2);
    }
}
