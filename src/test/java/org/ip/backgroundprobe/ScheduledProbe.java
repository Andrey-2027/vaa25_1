package org.ip.backgroundprobe;

import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Негативная fixture для {@link org.ip.ApplicationBackgroundBoundaryTest}: обычный
 * класс тестовых исходников (не бин, планировщиком не запускается), доказывающий,
 * что архитектурные правила действительно срабатывают на запрещённые примитивы.
 */
class ScheduledProbe {

    @Scheduled(fixedDelay = 60_000)
    void purge() {
    }

    @Async
    void recompute() {
    }

    @MetaScheduled
    void metaPurge() {
    }

    @MetaAsync
    void metaRecompute() {
    }
}

@Async
class ClassLevelAsyncProbe {
}

@MetaAsync
class MetaClassLevelAsyncProbe {
}

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Scheduled(fixedDelay = 60_000)
@interface MetaScheduled {
}

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Async
@interface MetaAsync {
}
