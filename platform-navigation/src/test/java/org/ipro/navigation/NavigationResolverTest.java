package org.ipro.navigation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NavigationResolverTest {
    @Test
    void removesEmptyBranchesAndSharesTheFilteredChildWithItsParentPage() {
        var result = new NavigationResolver().resolve(NavigationCatalogTest.catalog(), item -> !item.id().equals("first"));
        assertThat(result.roots()).extracting(NavigationTree.Node::id).containsExactly("root");
        var root = result.roots().getFirst();
        assertThat(root.groupsRecursive()).extracting(NavigationTree.Group::subsystemId).containsExactly("root", "child");
        assertThat(root.groupsRecursive().getLast().items()).extracting(NavigationItem::id).containsExactly("second");
        assertThat(root.groupsRecursive().getLast().items()).isEqualTo(root.children().getFirst().items());
        assertThat(new NavigationResolver().resolve(NavigationCatalogTest.catalog(), item -> false).roots()).isEmpty();
    }

    @Test
    void availabilityFailureDoesNotBecomeAPermissiveMenu() {
        assertThatThrownBy(() -> new NavigationResolver().resolve(NavigationCatalogTest.catalog(), item -> {
            throw new IllegalStateException("read gate failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("read gate failed");
    }
}
