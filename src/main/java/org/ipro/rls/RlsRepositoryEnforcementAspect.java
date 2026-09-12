package org.ipro.rls;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.ResolvableType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.ipro.metadata.SectionMetadataRegistry;
import org.ipro.metadata.TableSectionMetadataInfo;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.util.ClassUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Последняя автоматическая граница перед Spring Data repository.
 *
 * <p>Она закрывает custom/derived repository methods защищённых сущностей, даже если
 * application service не вызвал RLS вручную. Изменение только по id намеренно
 * запрещено: без загрузки объекта невозможно проверить dimension values.</p>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RlsRepositoryEnforcementAspect {

    @PersistenceContext
    private EntityManager entityManager;

    private final RlsDimensionRegistry registry;
    private final RlsPolicyEnforcer enforcer;
    private final SectionMetadataRegistry sectionRegistry;
    private final PlatformTransactionManager transactionManager;

    public RlsRepositoryEnforcementAspect(
            RlsDimensionRegistry registry,
            RlsPolicyEnforcer enforcer,
            SectionMetadataRegistry sectionRegistry,
            PlatformTransactionManager transactionManager) {
        this.registry = registry;
        this.enforcer = enforcer;
        this.sectionRegistry = sectionRegistry;
        this.transactionManager = transactionManager;
    }

    @Around("execution(* org.springframework.data.repository.Repository+.*(..)) || "
        + "execution(* org.springframework.data.jpa.repository.JpaSpecificationExecutor+.*(..)) || "
        + "execution(* org.springframework.data.repository.query.QueryByExampleExecutor+.*(..))")
    public Object enforce(ProceedingJoinPoint invocation) throws Throwable {
        Class<?> domainClass = resolveDomainClass(invocation.getThis());
        if (domainClass == null) {
            throw new RlsAccessDeniedException(
                "Cannot resolve Spring Data repository domain; operation denied");
        }
        if (RlsContext.isBypassed()) {
            return invocation.proceed();
        }

        TableSectionMetadataInfo section = sectionRegistry.findByRow(domainClass).orElse(null);
        if (section != null) {
            // A generic row repository cannot express the owner's mandatory predicate
            // for every derived/custom query. Deny the parallel entry point instead;
            // GenericOwnedSectionService is the only supported aggregate boundary.
            throw new RlsAccessDeniedException("Owned section repository access is not allowed for "
                + section.getKey() + "; use the aggregate section service");
        }
        if (!registry.policyOf(domainClass).protectedEntity()) {
            return invocation.proceed();
        }

        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setPropagationBehavior(DefaultTransactionDefinition.PROPAGATION_REQUIRED);
        TransactionStatus transaction = transactionManager.getTransaction(definition);
        try {
            Object result = enforceProtected(invocation, domainClass);
            transactionManager.commit(transaction);
            return result;
        } catch (Throwable failure) {
            if (!transaction.isCompleted()) {
                transactionManager.rollback(transaction);
            }
            throw failure;
        }
    }

    private Object enforceProtected(ProceedingJoinPoint invocation, Class<?> domainClass)
            throws Throwable {

        Method method = ((MethodSignature) invocation.getSignature()).getMethod();
        String methodName = method.getName();
        boolean delete = methodName.startsWith("delete") || methodName.startsWith("remove");
        boolean save = methodName.startsWith("save");
        boolean modifying = AnnotatedElementUtils.hasAnnotation(method, Modifying.class);
        boolean batchMutation = methodName.endsWith("InBatch");
        boolean flush = methodName.equals("flush");
        Query declaredQuery = AnnotatedElementUtils.findMergedAnnotation(method, Query.class);

        // Hibernate entity filters cannot protect native SQL. A protected repository
        // may use JPQL (filters remain attached to entity roots), but native queries
        // require a separately reviewed secured facade and are denied here.
        if (declaredQuery != null && declaredQuery.nativeQuery()) {
            throw new RlsAccessDeniedException("Native query for protected entity "
                + domainClass.getSimpleName() + " is not allowed through repository boundary");
        }

        // A bare flush can persist dirty managed instances without carrying an entity
        // argument, so there is no safe way to run the row-level write policy here.
        // Protected callers must use save/saveAndFlush (or an explicit typed use case).
        if (flush) {
            throw new RlsAccessDeniedException("Protected repository flush is not allowed; "
                + "use an enforced save operation");
        }

        // @Modifying queries execute bulk DML directly and do not run entity lifecycle
        // callbacks. Even when an entity happens to be passed as a parameter, it cannot
        // prove that every row affected by the query satisfies the write policy.
        if (modifying || batchMutation) {
            throw new RlsAccessDeniedException("Bulk mutation for protected entity "
                + domainClass.getSimpleName() + " is not allowed through repository boundary");
        }

        if (delete || save) {
            List<Object> entities = entityArguments(domainClass, invocation.getArgs());
            if (entities.isEmpty()) {
                throw new RlsAccessDeniedException("Protected repository operation "
                    + methodName + " on " + domainClass.getSimpleName()
                    + " requires entity values; id/bulk mutation is not allowed");
            }
            for (Object entity : entities) {
                if (delete) {
                    enforcer.requireDelete(entity);
                } else {
                    enforcer.requireUpdate(entity);
                }
            }
        } else {
            enforcer.requireReadable(domainClass, entityManager);
        }
        return invocation.proceed();
    }

    private static Class<?> resolveDomainClass(Object repositoryProxy) {
        Set<Class<?>> interfaces = ClassUtils.getAllInterfacesForClassAsSet(
            repositoryProxy.getClass());
        for (Class<?> candidate : interfaces) {
            if (!Repository.class.isAssignableFrom(candidate)) {
                continue;
            }
            Class<?> domainClass = ResolvableType.forClass(candidate)
                .as(Repository.class).getGeneric(0).resolve();
            if (domainClass != null && domainClass != Object.class) {
                return domainClass;
            }
        }
        return null;
    }

    private static List<Object> entityArguments(Class<?> domainClass, Object[] arguments) {
        List<Object> result = new ArrayList<>();
        for (Object argument : arguments) {
            collectEntities(domainClass, argument, result);
        }
        return result;
    }

    private static void collectEntities(Class<?> domainClass, Object value, List<Object> target) {
        if (value == null) {
            return;
        }
        if (domainClass.isInstance(value)) {
            target.add(value);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> collectEntities(domainClass, item, target));
            return;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int index = 0; index < length; index++) {
                collectEntities(domainClass, Array.get(value, index), target);
            }
        }
    }
}
