package org.ipro.rls.c5;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F-REST-READ-3, З2: C5 read key распознаётся только явной регистрацией, не по префиксу. */
class C5ReadPermissionRegistryTest {

    @Test
    void onlyExplicitlyRegisteredKeysAreRecognised() {
        C5ReadPermissionRegistry registry = new C5ReadPermissionRegistry();

        assertThat(registry.isRegisteredReadKey("ENTITY:org.example.Model")).isFalse();
        assertThat(registry.isRegisteredReadKey("REST:widgets:v1:LIST")).isFalse();
        assertThat(registry.isRegisteredReadKey("*")).isFalse();
        assertThat(registry.isRegisteredReadKey(null)).isFalse();

        registry.registerReadKey("ENTITY:org.example.Model");
        registry.registerReadKey("REST:widgets:v1:LIST");

        assertThat(registry.isRegisteredReadKey("ENTITY:org.example.Model")).isTrue();
        assertThat(registry.isRegisteredReadKey("REST:widgets:v1:LIST")).isTrue();
        assertThat(registry.isRegisteredReadKey("REST:widgets:v2:LIST")).isFalse();
        assertThat(registry.registeredKeys())
            .containsExactlyInAnyOrder("ENTITY:org.example.Model", "REST:widgets:v1:LIST");
    }

    @Test
    void blankAndUntrimmedKeysAreRejected() {
        C5ReadPermissionRegistry registry = new C5ReadPermissionRegistry();

        assertThatThrownBy(() -> registry.registerReadKey(" "))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.registerReadKey(" ENTITY:x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.registerReadKey(null))
            .isInstanceOf(NullPointerException.class);
    }
}
