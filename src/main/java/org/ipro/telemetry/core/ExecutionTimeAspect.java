package org.ipro.telemetry.core;

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

    private final OperationContext operationContext;

    public ExecutionTimeAspect(OperationContext operationContext) {
        this.operationContext = operationContext;
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
}