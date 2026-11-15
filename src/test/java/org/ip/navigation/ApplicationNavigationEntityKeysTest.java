package org.ip.navigation;

import org.ip.model.Workshop;
import org.ipro.form.link.FormRouteCatalog;
import org.ipro.form.link.PublishedFormRoute;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplicationNavigationEntityKeysTest {
    private final FormRouteCatalog routes = mock(FormRouteCatalog.class);
    private final org.ipro.navigation.NavigationEntityKeys keys =
        new ApplicationNavigationEntityKeys().navigationEntityKeys(routes);

    @Test
    void publishedTypeGetsItsRouteKeySoARenamedClassKeepsMenuSettings() {
        var route = mock(PublishedFormRoute.class);
        when(route.entityKey()).thenReturn("workshop");
        when(routes.find(Workshop.class)).thenReturn(Optional.of(route));
        assertThat(keys.keyOf(Workshop.class)).isEqualTo("workshop");
    }

    @Test
    void typeWithoutPublishedRouteGetsTheDerivedKey() {
        when(routes.find(Workshop.class)).thenReturn(Optional.empty());
        assertThat(keys.keyOf(Workshop.class)).isEqualTo("workshop");
        assertThat(org.ipro.navigation.NavigationEntityKeys.derive(org.ip.model.PrdSpec.class)).isEqualTo("prd-spec");
    }

    @Test
    void keysAreNotAvailableBeforeTheFormRegistryIsClosed() {
        when(routes.find(Workshop.class)).thenThrow(new IllegalStateException("registry is open"));
        assertThatThrownBy(() -> keys.keyOf(Workshop.class)).isInstanceOf(IllegalStateException.class);
    }
}
