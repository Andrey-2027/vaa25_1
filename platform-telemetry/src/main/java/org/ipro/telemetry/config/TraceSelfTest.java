package org.ipro.telemetry.config;

import org.ipro.telemetry.api.OperationScope;
import org.ipro.telemetry.api.Telemetry;
import org.ipro.telemetry.core.MdcKeys;
import org.slf4j.MDC;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

/**
 * Self-test L2-трассировки (включается ipro.telemetry.trace-self-test=true):
 * выполняет один native-запрос через Hibernate внутри операции с MDC-флагом
 * трассы — проверяется end-to-end вся цепочка: Inspector → Listener → дерево
 * фреймов → TraceDumpHandler (trace-файл + событие TRACE).
 */
public class TraceSelfTest implements ApplicationRunner {

    private final EntityManagerFactory entityManagerFactory;
    private final PlatformTransactionManager transactionManager;
    private final Telemetry telemetry;

    public TraceSelfTest(EntityManagerFactory entityManagerFactory,
                         PlatformTransactionManager transactionManager,
                         Telemetry telemetry) {
        this.entityManagerFactory = entityManagerFactory;
        this.transactionManager = transactionManager;
        this.telemetry = telemetry;
    }

    @Override
    public void run(ApplicationArguments args) {
        MDC.put(MdcKeys.USER, "system");
        MDC.put(MdcKeys.TRACE, "1");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try (OperationScope scope = telemetry.beginOperation("selftest:trace")) {
            tx.executeWithoutResult(status -> {
                try (EntityManager em = entityManagerFactory.createEntityManager()) {
                    em.createNativeQuery("SELECT count(*) FROM operation_log").getSingleResult();
                }
            });
        } finally {
            MDC.clear();
        }
    }
}