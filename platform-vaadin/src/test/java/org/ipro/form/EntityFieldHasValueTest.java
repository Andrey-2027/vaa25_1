package org.ipro.form;

import com.vaadin.flow.component.HasValue;
import com.vaadin.flow.component.grid.Grid;
import org.ipro.metadata.HasDisplayName;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class EntityFieldHasValueTest {
    record Choice(String name) implements HasDisplayName { public String getDisplayName() { return name; } }
    private static void choose(EntityField<Choice> field, Choice value) throws Exception {
        var method = EntityField.class.getDeclaredMethod("onSelectedInDialog", HasDisplayName.class);
        method.setAccessible(true); method.invoke(field, value);
    }

    @Test void standardEventsAndValueLambdasHaveSeparateCompatibleSemantics() throws Exception {
        var field = new EntityField<Choice>("Choice", term -> List.of());
        HasValue<EntityField.EntityValueChangeEvent<Choice>, Choice> standard = field;
        var events = new ArrayList<EntityField.EntityValueChangeEvent<Choice>>();
        var registration = standard.addValueChangeListener(events::add);
        var legacy = new ArrayList<Choice>();
        Consumer<Choice> oldConsumer = legacy::add;
        field.addValueChangeListener(oldConsumer); // Preserves the old void Consumer descriptor.
        var lambdaNames = new ArrayList<String>();
        var valueRegistration = field.addValueChangeListener(value -> lambdaNames.add(value.getDisplayName()));
        var methodReferences = new ArrayList<Choice>();
        field.addValueChangeListener(methodReferences::add); // No overload ambiguity.
        EntityField raw = field;
        raw.addValueChangeListener(value -> assertThat(value).isInstanceOf(Choice.class));
        var a = new Choice("A"); var b = new Choice("B");
        field.setValue(a); field.setValue(a);
        assertThat(events).hasSize(1); assertThat(legacy).isEmpty();
        choose(field, b);
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getHasValue()).isSameAs(field);
        assertThat(events.get(0).getOldValue()).isNull();
        assertThat(events.get(0).getValue()).isEqualTo(a);
        assertThat(events.get(0).isFromClient()).isFalse();
        assertThat(events.get(1).getOldValue()).isEqualTo(a);
        assertThat(events.get(1).isFromClient()).isTrue();
        assertThat(legacy).containsExactly(b); assertThat(lambdaNames).containsExactly("B");
        assertThat(methodReferences).containsExactly(b);
        registration.remove(); registration.remove(); valueRegistration.remove();
        field.removeValueChangeListener(oldConsumer);
        choose(field, a);
        assertThat(events).hasSize(2); assertThat(legacy).containsExactly(b);
        assertThat(lambdaNames).containsExactly("B");
        assertThat(standard.getOptionalValue()).contains(a);
    }

    @Test void explicitEventSubscriptionsClearAndRequiredReadOnlyFollowHasValue() throws Exception {
        var field = new EntityField<Choice>("Choice", term -> List.of());
        var events = new ArrayList<EntityField.EntityValueChangeEvent<Choice>>();
        var first = field.addHasValueChangeListener(events::add);
        field.addValueChangeListener((EntityField.EntityValueChangeEvent<Choice> event) -> assertThat(event.getHasValue()).isSameAs(field));
        assertThat(field.isEmpty()).isTrue(); assertThat(field.getEmptyValue()).isNull();
        field.setRequiredIndicatorVisible(true); assertThat(field.isRequiredIndicatorVisible()).isTrue();
        field.setValue(new Choice("A")); field.setReadOnly(true);
        choose(field, new Choice("B"));
        assertThat(field.getValue().name()).isEqualTo("A"); assertThat(events).hasSize(1);
        field.clear(); // Server assignments remain possible in read-only mode.
        assertThat(field.isEmpty()).isTrue(); assertThat(events).hasSize(2);
        assertThat(events.getLast().isFromClient()).isFalse();
        first.remove(); field.setReadOnly(false); field.setValue(new Choice("C"));
        assertThat(events).hasSize(2);
    }
}
