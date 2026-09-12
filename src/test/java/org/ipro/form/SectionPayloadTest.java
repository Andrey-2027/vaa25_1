package org.ipro.form;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SectionPayloadTest {

    @Test
    void attachedEmptyIsDifferentFromAbsent() {
        SectionPayload<String> attachedEmpty = SectionPayload.attached(List.of());
        SectionPayload<String> absent = SectionPayload.absent();

        assertThat(attachedEmpty.isAttached()).isTrue();
        assertThat(attachedEmpty.rows()).isEmpty();
        assertThat(absent.isAbsent()).isTrue();
        assertThat(absent.rows()).isEmpty();
        assertThat(attachedEmpty).isNotEqualTo(absent);
    }

    @Test
    void absentPayloadCannotCarryRows() {
        assertThatThrownBy(() -> new SectionPayload<>(SectionPresence.ABSENT, List.of("row")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ABSENT");
    }

    @Test
    void payloadCopiesRowsAndExposesNoMutableList() {
        List<String> source = new ArrayList<>(List.of("one"));
        SectionPayload<String> payload = SectionPayload.attached(source);

        source.add("two");

        assertThat(payload.rows()).containsExactly("one");

        assertThatThrownBy(() -> payload.rows().add("two"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void payloadRejectsNullPresenceRowsAndRowElements() {
        assertThatThrownBy(() -> new SectionPayload<String>(null, List.of()))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("presence");
        assertThatThrownBy(() -> new SectionPayload<String>(SectionPresence.ATTACHED, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("rows");
        assertThatThrownBy(() -> SectionPayload.attached(java.util.Arrays.asList("ok", null)))
            .isInstanceOf(NullPointerException.class);
    }
}
