package org.ipro.register;

import jakarta.persistence.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class RegisterDescriptorTest {
    @Test
    void preservesOrderedInheritedKeyMappingsTypesAndResources() {
        var descriptor = RegisterDescriptor.describe(Price.class);
        assertThat(descriptor.entityType()).isEqualTo(Price.class);
        assertThat(descriptor.entityName()).isEqualTo("DescriptorPrice");
        assertThat(descriptor.keyChangeAllowed()).isFalse();
        assertThat(descriptor.dimensionNames()).containsExactly("item", "channel");
        assertThat(descriptor.keyColumns()).containsExactly("item_id", "channel", "period_date");
        assertThat(descriptor.dimensions().getFirst().mapping()).isEqualTo(RegisterDescriptor.Mapping.TO_ONE);
        assertThat(descriptor.dimensions().getFirst().javaType()).isEqualTo(Item.class);
        assertThat(descriptor.period().orElseThrow().javaType()).isEqualTo(LocalDateTime.class);
        assertThat(descriptor.resources().getFirst().columnName()).isEmpty();
        assertThatThrownBy(() -> descriptor.dimensions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> descriptor.resources().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> descriptor.keyColumns().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void distinguishesAperiodicDeclarationsAndDoesNotManufacturePhysicalResourceNames() {
        var descriptor = RegisterDescriptor.describe(CurrentValue.class);
        assertThat(descriptor.entityName()).isEqualTo("CurrentValue");
        assertThat(descriptor.periodic()).isFalse();
        assertThat(descriptor.period()).isEmpty();
        assertThat(descriptor.keyColumns()).containsExactly("code");
        assertThat(descriptor.resources().getFirst().javaType()).isEqualTo(int.class);
    }

    @Test
    void invalidDeclarationsCannotPublishDescriptors() {
        assertThatThrownBy(() -> RegisterDescriptor.describe(Broken.class))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("KEY_UPDATABLE");
        assertThatThrownBy(() -> RegisterDescriptor.describe(Item.class))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("@RegisterMetadata");
    }

    @Test
    void collectionResourceIsNotMisclassifiedAsBasicMapping() {
        var descriptor = RegisterDescriptor.describe(CollectionValue.class);
        assertThat(descriptor.resources().getFirst().mapping()).isEqualTo(RegisterDescriptor.Mapping.OTHER);
        assertThat(descriptor.resources().getFirst().columnName()).isEmpty();
    }

    @Test
    void keyChangeRequiresAnExplicitRegisterDeclaration() {
        assertThat(RegisterDescriptor.describe(OptInValue.class).keyChangeAllowed()).isTrue();
        assertThat(RegisterDescriptor.describe(CurrentValue.class).keyChangeAllowed()).isFalse();
    }

    @Entity @Table(uniqueConstraints = @UniqueConstraint(columnNames = "code"))
    @RegisterMetadata(dimensions = "code", resources = "value", keyChangeAllowed = true,
        displayName = "Проверочный регистр")
    static class OptInValue {
        @Id Long id;
        @Column(name = "code", nullable = false) String code;
        int value;
    }

    @Entity @Table(uniqueConstraints = @UniqueConstraint(columnNames = "code"))
    @RegisterMetadata(dimensions = "code", resources = "values")
    static class CollectionValue {
        @Id Long id;
        @Column(name = "code", nullable = false, updatable = false) String code;
        @ElementCollection java.util.List<String> values;
    }

    @Entity static class Item { @Id Long id; }
    @MappedSuperclass static class PriceFields {
        @Id Long id;
        @ManyToOne @JoinColumn(name = "item_id", nullable = false, updatable = false) Item item;
        @Column(name = "channel", nullable = false, updatable = false) String channel;
        @Column(name = "period_date", nullable = false, updatable = false) LocalDateTime period;
        int value;
    }
    @Entity(name = "DescriptorPrice")
    @Table(uniqueConstraints = @UniqueConstraint(columnNames = {"item_id", "channel", "period_date"}))
    @RegisterMetadata(dimensions = {"item", "channel"}, period = "period", resources = "value")
    static class Price extends PriceFields { }
    @Entity @Table(uniqueConstraints = @UniqueConstraint(columnNames = "code"))
    @RegisterMetadata(dimensions = "code", resources = "value")
    static class CurrentValue {
        @Id Long id;
        @Column(name = "code", nullable = false, updatable = false) String code;
        int value;
    }
    @Entity @Table(uniqueConstraints = @UniqueConstraint(columnNames = "code"))
    @RegisterMetadata(dimensions = "code", resources = "value")
    static class Broken {
        @Id Long id;
        @Column(name = "code", nullable = false) String code;
        int value;
    }
}
