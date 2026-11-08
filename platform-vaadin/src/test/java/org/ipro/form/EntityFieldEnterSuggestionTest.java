package org.ipro.form;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.textfield.TextField;
import org.ipro.fetch.instance.InstanceNameBridge;
import org.ipro.metadata.HasDisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class EntityFieldEnterSuggestionTest {

    private record Choice(String name) implements HasDisplayName {
        @Override
        public String getDisplayName() {
            return name;
        }
    }

    private static TextField textFieldOf(Component root) {
        return findText(root).orElseThrow();
    }

    private static Optional<TextField> findText(Component component) {
        if (component instanceof TextField textField) {
            return Optional.of(textField);
        }
        return component.getChildren().map(EntityFieldEnterSuggestionTest::findText)
            .flatMap(Optional::stream).findFirst();
    }

    private static void focusSuggestion(EntityField<?> field, int index) throws Exception {
        Field focused = EntityField.class.getDeclaredField("focusedRowIndex");
        focused.setAccessible(true);
        focused.setInt(field, index);
    }

    @Test
    void enterChoosesTheOnlyMatchAndReplacesThePartialText() {
        var item = new Choice("СКЕ");
        var field = new EntityField<Choice>("Choice", term -> List.of(item));
        var text = textFieldOf(field);
        var changes = new ArrayList<EntityField.ValueChange<Choice>>();
        field.addChangeListener(changes::add);

        text.setValue("СК");

        assertThat(field.getValue()).isNull();
        assertThat(field.chooseSuggestionOnEnter()).isTrue();
        assertThat(field.getValue()).isSameAs(item);
        assertThat(text.getValue()).isEqualTo(InstanceNameBridge.displayName(item));
        assertThat(changes).singleElement().satisfies(change -> {
            assertThat(change.oldValue()).isNull();
            assertThat(change.value()).isSameAs(item);
            assertThat(change.source()).isEqualTo(ValueChangeSource.USER);
        });
    }

    @Test
    void enterChoosesTheHighlightedMatchWhenSeveralAreOffered() throws Exception {
        var first = new Choice("A1");
        var second = new Choice("A2");
        var field = new EntityField<Choice>("Choice", term -> List.of(first, second));
        var text = textFieldOf(field);

        text.setValue("A");
        focusSuggestion(field, 1);

        assertThat(field.chooseSuggestionOnEnter()).isTrue();
        assertThat(field.getValue()).isSameAs(second);
        assertThat(text.getValue()).isEqualTo(InstanceNameBridge.displayName(second));
    }

    @Test
    void enterDoesNothingForSeveralMatchesWithoutAHighlightedRow() {
        var first = new Choice("A1");
        var second = new Choice("A2");
        var field = new EntityField<Choice>("Choice", term -> List.of(first, second));
        var text = textFieldOf(field);

        text.setValue("A");

        assertThat(field.chooseSuggestionOnEnter()).isFalse();
        assertThat(field.getValue()).isNull();
        assertThat(text.getValue()).isEqualTo("A");
    }

    @Test
    void aNewSearchClearsAHighlightLeftFromThePreviousOne() throws Exception {
        var first = new Choice("A1");
        var second = new Choice("A2");
        var only = new Choice("B1");
        var results = new ArrayList<>(List.of(first, second));
        var field = new EntityField<Choice>("Choice", term -> results);
        var text = textFieldOf(field);

        text.setValue("A");
        focusSuggestion(field, 1);
        results.clear();
        results.add(only);
        text.setValue("B");

        assertThat(field.chooseSuggestionOnEnter()).isTrue();
        assertThat(field.getValue()).isSameAs(only);
        assertThat(text.getValue()).isEqualTo(InstanceNameBridge.displayName(only));
    }
}
