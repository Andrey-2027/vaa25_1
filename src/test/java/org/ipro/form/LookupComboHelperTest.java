package org.ipro.form;

import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.data.provider.Query;
import org.ip.model.Journal;
import org.ipro.crud.EntityLookup;
import org.ipro.metadata.ColumnPath;
import org.ipro.metadata.EntityMetadataInfo;
import org.ipro.metadata.MetadataResolver;
import org.ipro.metadata.annotation.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D3.5.2: общий lazy lookup для комбобоксов фильтров вместо {@code findAll} всей таблицы.
 */
class LookupComboHelperTest {

    @Test
    void suggestIsBoundedAndPassesTermThrough() {
        EntityLookup lookup = mock(EntityLookup.class);
        Journal first = mock(Journal.class);
        Journal second = mock(Journal.class);
        when(lookup.search(eq(Journal.class), anyCollection(), eq("lk"), anyInt()))
            .thenReturn(List.of(first, second));

        List<Object> got = LookupComboHelper.suggest(
            Journal.class, lookup, failingResolver(), "lk");

        assertThat(got).containsExactly(first, second);
        verify(lookup).search(eq(Journal.class), eq(List.of()),
            eq("lk"), eq(LookupComboHelper.SUGGESTION_LIMIT));
    }

    @Test
    void suggestUsesTextSelectColumnsWhenMetadataKnowsThem() {
        EntityLookup lookup = mock(EntityLookup.class);
        when(lookup.search(any(), anyCollection(), anyString(), anyInt()))
            .thenReturn(List.of());
        MetadataResolver resolver = mock(MetadataResolver.class);
        EntityMetadataInfo meta = mock(EntityMetadataInfo.class);
        ColumnPath code = mock(ColumnPath.class);
        when(code.getResolvedType()).thenReturn(FieldType.TEXT);
        when(code.getKey()).thenReturn("code");
        ColumnPath number = mock(ColumnPath.class);
        when(number.getResolvedType()).thenReturn(FieldType.INTEGER);
        when(number.getKey()).thenReturn("id");
        when(meta.getSelectColumnPaths()).thenReturn(List.of(code, number));
        when(resolver.resolve(Journal.class)).thenReturn(meta);

        LookupComboHelper.suggest(Journal.class, lookup, resolver, "a");

        verify(lookup).search(eq(Journal.class), eq(List.of("code")),
            eq("a"), eq(LookupComboHelper.SUGGESTION_LIMIT));
    }

    @Test
    void suggestRequiresEntityAndLookup() {
        EntityLookup lookup = mock(EntityLookup.class);
        assertThatThrownBy(() -> LookupComboHelper.suggest(null, lookup, failingResolver(), ""))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> LookupComboHelper.suggest(Journal.class, null, failingResolver(), ""))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void installedProviderFetchesBoundedSuggestions() {
        EntityLookup lookup = mock(EntityLookup.class);
        Journal found = mock(Journal.class);
        when(lookup.search(eq(Journal.class), anyCollection(), eq("fi"), anyInt()))
            .thenReturn(List.of(found));
        ComboBox<Object> box = new ComboBox<>();

        LookupComboHelper.installSuggestItems(box, Journal.class, lookup, failingResolver());

        List<Object> fetched = ((com.vaadin.flow.data.provider.DataProvider) box.getDataProvider())
            .fetch(new Query(0, 50, List.of(), null, "fi"))
            .toList();
        assertThat(fetched).containsExactly(found);
        verify(lookup).search(eq(Journal.class), eq(List.of()),
            eq("fi"), eq(LookupComboHelper.SUGGESTION_LIMIT));
    }

    @Test
    void restoreSavedSelectionLoadsSingleRow() {
        EntityLookup lookup = mock(EntityLookup.class);
        Journal journal = mock(Journal.class);
        when(lookup.findSelectedById(Journal.class, 7L)).thenReturn(Optional.of(journal));
        ComboBox<Journal> box = new ComboBox<>();
        // Порядок как в проде (valueWidgetFor): сначала провайдер, затем restore —
        // пустой ComboBox отклоняет setValue исключением самого Vaadin.
        LookupComboHelper.installSuggestItems(box, Journal.class, lookup, failingResolver());

        LookupComboHelper.restoreSavedSelection(box, Journal.class, "7", lookup);

        assertThat(box.getValue()).isSameAs(journal);
        assertThat(box.getHelperText()).isNullOrEmpty();
        verify(lookup, never()).search(any(), anyCollection(), anyString(), anyInt());
    }

    @Test
    void restoreSavedSelectionIsHonestWhenRowIsGone() {
        EntityLookup lookup = mock(EntityLookup.class);
        when(lookup.findSelectedById(Journal.class, 7L)).thenReturn(Optional.empty());
        ComboBox<Journal> box = new ComboBox<>();

        LookupComboHelper.restoreSavedSelection(box, Journal.class, "7", lookup);

        assertThat(box.getValue()).isNull();
        assertThat(box.getHelperText()).contains("7");
    }

    @Test
    void restoreSavedSelectionRejectsGarbageIdWithoutQuery() {
        EntityLookup lookup = mock(EntityLookup.class);
        ComboBox<Journal> box = new ComboBox<>();

        LookupComboHelper.restoreSavedSelection(box, Journal.class, "not-an-id", lookup);

        assertThat(box.getValue()).isNull();
        assertThat(box.getHelperText()).contains("not-an-id");
        verifyNoInteractions(lookup);
    }

    @Test
    void restoreSavedSelectionIgnoresBlank() {
        EntityLookup lookup = mock(EntityLookup.class);
        ComboBox<Journal> box = new ComboBox<>();

        LookupComboHelper.restoreSavedSelection(box, Journal.class, "  ", lookup);
        LookupComboHelper.restoreSavedSelection(box, Journal.class, null, lookup);

        assertThat(box.getValue()).isNull();
        assertThat(box.getHelperText()).isNullOrEmpty();
        verifyNoInteractions(lookup);
    }

    private static MetadataResolver failingResolver() {
        MetadataResolver resolver = mock(MetadataResolver.class);
        when(resolver.resolve(any())).thenThrow(new IllegalStateException("no metadata"));
        return resolver;
    }
}
