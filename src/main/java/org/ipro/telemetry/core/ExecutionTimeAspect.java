package org.ipro.telemetry.core;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

/**
 * Замер времени вызовов сервисного слоя (основной перехват) и методов,
 * помеченных @Measured (opt-in вне сервисов). Создаёт фреймы в
 * OperationContext; для корневого фрейма происходит финализация операции.
 * <p>
 * Известное ограничение Spring AOP: self-invocation (this.b()) минует
 * прокси — фрейм для b не создаётся, итоги ложатся на внешний фрейм.
 */
@Aspect
@Component
public class ExecutionTimeAspect {

    private static final int MAX_SNAPSHOT_CHARS = 16_384;

    private final OperationContext operationContext;
    private final boolean entityDataEnabled;

    public ExecutionTimeAspect(OperationContext operationContext, boolean entityDataEnabled) {
        this.operationContext = operationContext;
        this.entityDataEnabled = entityDataEnabled;
    }

    @Pointcut("execution(* org.ip.service..*(..))")
    void serviceLayer() {
    }

    @Pointcut("@annotation(org.ipro.telemetry.api.Measured)")
    void measured() {
    }

    @Around("serviceLayer() || measured()")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        String name = pjp.getSignature().getDeclaringType().getSimpleName()
                + "." + pjp.getSignature().getName();
        Frame frame = operationContext.beginFrame(name);
        if (frame == null) {
            return pjp.proceed();
        }
        captureEntityContext(pjp.getArgs());
        Throwable failure = null;
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            operationContext.endFrame(frame, frame.elapsedNanos(), failure);
        }
    }

    /**
     * Из аргументов метода (например save(entity)) извлекает сущность и её
     * id (через рефлексию getId) и кладёт в контекст операции — виден в
     * trace-файле и в entity/entity_id события. Пропускает примитивы,
     * коллекции и объекты фреймворков; не перезаписывает уже заданный
     * контекст (например entityId из openItemForm).
     */
    private void captureEntityContext(Object[] args) {
        Operation operation = operationContext.currentOperation();
        if (operation == null) {
            return;
        }
        for (Object arg : args) {
            if (arg == null || !isEntityLike(arg)) {
                continue;
            }
            if (operation.getContextValue(MdcKeys.ENTITY_ID) == null) {
                Object id = findId(arg);
                if (id != null) {
                    operationContext.putContext(MdcKeys.ENTITY, arg.getClass().getSimpleName());
                    operationContext.putContext(MdcKeys.ENTITY_ID, id.toString());
                }
            }
            if (entityDataEnabled
                    && operation.getContextValue(MdcKeys.ENTITY_DATA) == null) {
                String snapshot = EntitySnapshot.render(arg, MAX_SNAPSHOT_CHARS);
                if (snapshot != null) {
                    operationContext.putEntityData(snapshot);
                }
            }
            break;
        }
    }

    private static boolean isEntityLike(Object arg) {
        if (arg instanceof CharSequence || arg instanceof Number || arg instanceof Boolean
                || arg instanceof Character || arg instanceof Iterable || arg instanceof Map
                || arg instanceof Optional) {
            return false;
        }
        Class<?> type = arg.getClass();
        if (type.isArray() || type.isEnum() || type.isAnnotation()) {
            return false;
        }
        String name = type.getName();
        if (name.startsWith("java.") || name.startsWith("jakarta.")
                || name.startsWith("org.springframework.") || name.startsWith("org.hibernate.")
                || name.startsWith("com.vaadin.") || name.startsWith("org.vaadin.")) {
            return false;
        }
        return hasNoArgMethod(type, "getId");
    }

    private static Object findId(Object arg) {
        try {
            Method getId = arg.getClass().getMethod("getId");
            return getId.invoke(arg);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static boolean hasNoArgMethod(Class<?> type, String methodName) {
        try {
            type.getMethod(methodName);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}