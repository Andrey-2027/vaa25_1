package org.ipro.register.config;

import org.ipro.metadata.ManagedEntityCatalog;
import org.ipro.metadata.MetadataResolver;
import org.ipro.register.RegisterRegistry;
import org.ipro.register.RegisterStartupValidator;
import org.ipro.register.RecorderOrderClockBootstrap;
import org.ipro.register.RecorderSetDeleteParticipant;
import org.ipro.register.RecorderTypeRegistry;
import org.ipro.register.RecorderSetContributionParticipant;
import org.ipro.register.RecorderSetWriteAuthorizer;
import org.ipro.register.RlsRecorderSetWriteAuthorizer;
import org.ipro.register.RegisterRecords;
import org.ipro.register.RegisterKeyChanges;
import org.ipro.register.RegisterKeyChangePermissions;
import org.ipro.register.RegisterKeyWriteGuardInstaller;
import org.ipro.data.EntityDataAccess;
import org.ipro.events.EntityEventPublisher;
import org.ipro.lifecycle.EntityLifecycleRegistry;
import org.ipro.rls.AccessService;
import org.ipro.rls.RlsCurrentUser;
import org.ipro.rls.RlsDimensionRegistry;
import org.ipro.rls.c5.C5OperationPermissionRegistry;
import org.ipro.rls.c5.C5PermissionDecisionService;
import org.ipro.telemetry.api.EventSink;
import org.ipro.crud.AggregateSaveContributionParticipant;
import org.ipro.data.CanonicalDeleteParticipant;
import org.ipro.rls.RlsFilterActivator;
import org.ipro.rls.RlsPolicyEnforcer;
import jakarta.validation.Validator;
import jakarta.persistence.EntityManager;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;

import jakarta.persistence.EntityManagerFactory;

/** Shared declaration infrastructure; persistence ownership remains with the existing entity catalog. */
@AutoConfiguration
@EntityScan("org.ipro.register.persistence")
public class RegisterAutoConfiguration {
    @Bean
    public RegisterKeyWriteGuardInstaller registerKeyWriteGuardInstaller(EntityManagerFactory factory,
                                                                         RegisterRegistry registers) {
        // Report all declaration errors before a Hibernate guard can fail on
        // only the first broken mapping encountered in the persistence unit.
        registers.initialize();
        return new RegisterKeyWriteGuardInstaller(factory);
    }

    @Bean
    public RegisterKeyChangePermissions registerKeyChangePermissions(RegisterRegistry registers,
            C5OperationPermissionRegistry operations, RlsDimensionRegistry dimensions,
            C5PermissionDecisionService decisions, RlsCurrentUser currentUser, AccessService access) {
        return new RegisterKeyChangePermissions(registers, operations, dimensions, decisions, currentUser, access);
    }

    @Bean
    public RegisterKeyChanges registerKeyChanges(EntityManager entityManager, RegisterRegistry registers,
            RegisterKeyChangePermissions permissions, EntityDataAccess reads, RlsPolicyEnforcer policies,
            Validator validator, EntityLifecycleRegistry lifecycles, EntityEventPublisher events,
            RlsCurrentUser currentUser, java.util.Optional<EventSink> audit, PlatformTransactionManager transactionManager) {
        return new RegisterKeyChanges(entityManager, registers, permissions, reads, policies, validator,
            lifecycles, events, currentUser, audit, transactionManager);
    }

    @Bean
    public RegisterRegistry registerRegistry(ManagedEntityCatalog catalog, RecorderTypeRegistry recorderTypeRegistry) {
        return new RegisterRegistry(catalog, recorderTypeRegistry);
    }

    @Bean
    public RegisterStartupValidator registerStartupValidator(RegisterRegistry registry,
                                                              RecorderOrderClockBootstrap clocks) {
        return new RegisterStartupValidator(registry, clocks);
    }

    @Bean
    public RecorderTypeRegistry recorderTypeRegistry(ManagedEntityCatalog catalog,
                                                      EntityManagerFactory entityManagerFactory,
                                                      MetadataResolver metadataResolver) {
        return new RecorderTypeRegistry(catalog, entityManagerFactory, metadataResolver);
    }

    @Bean
    public SmartInitializingSingleton recorderTypeStartupValidator(RecorderTypeRegistry registry) {
        return registry::initialize;
    }

    @Bean
    public RecorderOrderClockBootstrap recorderOrderClockBootstrap(EntityManagerFactory entityManagerFactory,
                                                                    PlatformTransactionManager transactionManager) {
        return new RecorderOrderClockBootstrap(entityManagerFactory, transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean(RecorderSetWriteAuthorizer.class)
    public RecorderSetWriteAuthorizer recorderSetWriteAuthorizer(RlsPolicyEnforcer rlsPolicyEnforcer) {
        return new RlsRecorderSetWriteAuthorizer(rlsPolicyEnforcer);
    }

    @Bean
    public RegisterRecords registerRecords(EntityManager entityManager, RegisterRegistry registers,
            RecorderTypeRegistry recorderTypes, Validator validator, RlsFilterActivator filters,
            RecorderSetWriteAuthorizer authorizer, PlatformTransactionManager transactionManager) {
        return new RegisterRecords(entityManager, registers, recorderTypes, validator, filters,
            authorizer, transactionManager);
    }

    @Bean
    public AggregateSaveContributionParticipant recorderSetContributionParticipant(
            EntityManager entityManager, RegisterRegistry registers,
            RecorderTypeRegistry recorderTypes, Validator validator,
            RlsFilterActivator rlsFilterActivator, RecorderSetWriteAuthorizer writeAuthorizer) {
        return new RecorderSetContributionParticipant(entityManager, registers, recorderTypes,
            validator, rlsFilterActivator, writeAuthorizer);
    }

    @Bean
    public CanonicalDeleteParticipant recorderSetDeleteParticipant(EntityManager entityManager,
            RegisterRegistry registers, RecorderTypeRegistry recorderTypes,
            Validator validator, RlsFilterActivator rlsFilterActivator, RecorderSetWriteAuthorizer writeAuthorizer) {
        return new RecorderSetDeleteParticipant(entityManager, registers, recorderTypes,
            validator, rlsFilterActivator, writeAuthorizer);
    }
}
