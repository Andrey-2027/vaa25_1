package org.ip.config;

import jakarta.persistence.EntityManagerFactory;
import org.ip.model.NomenclatureAccountingObject;
import org.ipro.data.EntityDataAccess;
import org.ipro.register.RegisterRegistry;
import org.ipro.register.RegisterSliceReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the canonical slice reader without exposing a JPA factory to application services. */
@Configuration(proxyBeanMethods = false)
public class AccountingObjectRegisterConfiguration {
    @Bean
    RegisterSliceReader<NomenclatureAccountingObject> accountingObjectSliceReader(
            EntityDataAccess access, RegisterRegistry registers, EntityManagerFactory factory) {
        return new RegisterSliceReader<>(NomenclatureAccountingObject.class, access, registers, factory);
    }
}
