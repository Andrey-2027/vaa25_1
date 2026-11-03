package com.example.registerconsumer;

import com.example.registerconsumer.model.Item;
import com.example.registerconsumer.model.ItemRate;
import com.example.registerconsumer.model.RegionRate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.ipro.crud.BaseEntity;
import org.ipro.data.EntityDataAccess;
import org.ipro.data.EntityDescriptorCatalog;
import org.ipro.data.EntityExposure;
import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.annotation.EntityKind;
import org.ipro.register.RecorderOrderClockBootstrap;
import org.ipro.register.RegisterKeyChanges;
import org.ipro.register.RegisterKeyWriteGuardInstaller;
import org.ipro.register.RegisterKeyWritePermissions;
import org.ipro.register.RegisterKeyWrites;
import org.ipro.register.RegisterNamedOperations;
import org.ipro.register.RegisterPeriodicity;
import org.ipro.register.RegisterReaderFactory;
import org.ipro.register.RegisterRegistry;
import org.ipro.register.RegisterMetadata;
import org.ipro.register.RegisterMetadataValidator;
import org.ipro.register.RegisterStartupValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2.1 bootstrap acceptance: a clean Spring start validates both declarations, publishes the
 * register descriptor snapshot, classifies the types as INFORMATION_REGISTER and gets readers,
 * commands, guards and the order clock from the starter.
 */
@SpringBootTest(properties = "consumer.demo-password=register-consumer-test-only")
class RegisterConsumerBootstrapTest {

    @Autowired RegisterRegistry registry;
    @Autowired RegisterReaderFactory readers;
    @Autowired RegisterKeyWrites keyWrites;
    @Autowired RegisterKeyChanges keyChanges;
    @Autowired RegisterKeyWritePermissions keyWritePermissions;
    @Autowired RegisterNamedOperations namedOperations;
    @Autowired EntityDescriptorCatalog catalog;
    @Autowired EntityDataAccess data;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate jdbc;

    @Test
    void cleanStartupValidatesBothDeclarationsAndClassifiesThemAsIndependentRegisters() {
        assertThat(registry.descriptors()).hasSize(2);

        var region = registry.require(RegionRate.class);
        assertThat(region.entityName()).isEqualTo("RegionRate");
        assertThat(region.periodic()).isFalse();
        assertThat(region.periodicity()).isEqualTo(RegisterPeriodicity.EXACT);
        assertThat(region.dimensions()).extracting(field -> field.name()).containsExactly("regionCode");
        assertThat(region.resources()).extracting(field -> field.name()).containsExactly("rate");
        assertThat(region.rowKeyColumns()).containsExactly("region_code");
        assertThat(region.keyWriteAllowed()).isTrue();
        assertThat(region.keyChangeAllowed()).isFalse();
        assertThat(region.genericWritesAllowed()).isTrue();
        assertThat(region.displayName()).isEqualTo("Региональные ставки");

        var item = registry.require(ItemRate.class);
        assertThat(item.periodic()).isTrue();
        assertThat(item.periodicity()).isEqualTo(RegisterPeriodicity.DAY);
        assertThat(item.dimensions()).extracting(field -> field.name()).containsExactly("item");
        assertThat(item.period().orElseThrow().name()).isEqualTo("period");
        assertThat(item.resources()).extracting(field -> field.name()).containsExactly("rate");
        assertThat(item.rowKeyColumns()).containsExactly("item_id", "period_date");
        assertThat(item.dimensionGroupColumns()).containsExactly("item_id");
        assertThat(item.keyWriteAllowed()).isTrue();
        assertThat(item.keyChangeAllowed()).isFalse();
        assertThat(item.displayName()).isEqualTo("Ставки по номенклатуре");

        assertThat(catalog.classificationOf(RegionRate.class).kind()).isEqualTo(EntityKind.INFORMATION_REGISTER);
        assertThat(catalog.classificationOf(ItemRate.class).kind()).isEqualTo(EntityKind.INFORMATION_REGISTER);
        assertThat(catalog.classificationOf(RegionRate.class).recorderOwned()).isFalse();
        assertThat(data.descriptor(RegionRate.class).exposure()).isEqualTo(EntityExposure.STANDARD_ROOT);
        assertThat(data.descriptor(ItemRate.class).exposure()).isEqualTo(EntityExposure.STANDARD_ROOT);
    }

    @Test
    void theStarterWiresReadersCommandsGuardsAndTheOrderClockWithoutApplicationBeans() {
        assertThat(readers).isNotNull();
        assertThat(keyWrites).isNotNull();
        assertThat(keyChanges).isNotNull();
        assertThat(keyWritePermissions).isNotNull();
        assertThat(context.getBeansOfType(RegisterStartupValidator.class)).hasSize(1);
        assertThat(context.getBeansOfType(RegisterKeyWriteGuardInstaller.class)).hasSize(1);
        assertThat(context.getBeansOfType(RecorderOrderClockBootstrap.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(RegisterRegistry.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(RegisterReaderFactory.class)).hasSize(1);
    }

    @Test
    void namedOperationsAreDescriptionsAndRemainSeparateFromGrants() {
        assertThat(namedOperations.operations())
            .extracting(RegisterNamedOperations.Operation::permissionId)
            .containsExactlyInAnyOrder(
                RegisterKeyWritePermissions.setPermissionId(RegionRate.class),
                RegisterKeyWritePermissions.deletePermissionId(RegionRate.class),
                RegisterKeyWritePermissions.setPermissionId(ItemRate.class),
                RegisterKeyWritePermissions.deletePermissionId(ItemRate.class));
        assertThat(namedOperations.operations())
            .noneMatch(operation -> operation.permissionId().contains("CHANGE_KEY"));
        assertThat(namedOperations.operations())
            .allSatisfy(operation -> assertThat(operation.title()).isNotBlank());
        assertThat(keyWritePermissions.canWriteKey(RegionRate.class, false))
            .as("declaring an operation does not grant it")
            .isFalse();
    }

    @Test
    void aCleanDatabaseContainsBothRegisterTables() {
        Integer tables = jdbc.queryForObject(
            "select count(*) from information_schema.tables where lower(table_name) in ('region_rate', 'item_rate')",
            Integer.class);
        assertThat(tables).isEqualTo(2);
        Integer itemTable = jdbc.queryForObject(
            "select count(*) from information_schema.tables where lower(table_name) = 'register_item'",
            Integer.class);
        assertThat(itemTable).isEqualTo(1);
    }

    @Test
    void aBrokenDeclarationIsRejectedByTheValidatorAndStaysOutsideTheMainScan() {
        assertThat(RegisterMetadataValidator.validate(BrokenRegister.class))
            .extracting(RegisterMetadataValidator.Violation::code)
            .contains(RegisterMetadataValidator.Code.KEY_UPDATABLE,
                RegisterMetadataValidator.Code.UNIQUE_MISSING);
        assertThat(context.getBean(ManagedEntityCatalog.class).managedEntityClasses())
            .doesNotContain(BrokenRegister.class);
        assertThat(registry.find(BrokenRegister.class)).isEmpty();
    }

    /** Deliberately invalid: mutable key and no exact UNIQUE. Never part of the main scan. */
    @Entity
    @Table(name = "broken_register")
    @RegisterMetadata(dimensions = "code", resources = "amount", keyWriteAllowed = true,
        displayName = "Сломанный регистр")
    static class BrokenRegister extends BaseEntity {
        @Column(name = "code", nullable = false, length = 20)
        private String code;

        @Column(name = "amount", precision = 19, scale = 4)
        private BigDecimal amount;
    }
}
