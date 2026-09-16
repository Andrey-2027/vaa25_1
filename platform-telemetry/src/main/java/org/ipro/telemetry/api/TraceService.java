package org.ipro.telemetry.api;

import java.time.Instant;
import java.util.List;

/**
 * Управление L2-трассировкой по требованию (этап 7). Окна трассировки
 * хранятся в trace_settings (переживают рестарт) и кэшируются в памяти.
 * Пока окно активно — операции пользователя (или всех, userId "*")
 * собирают детальную трассу: полное дерево фреймов с текстами SQL
 * (без параметров — Hibernate 7 не отдаёт bind-значения без внешней
 * библиотеки), пишется trace-файл и событие EventType.TRACE.
 */
public interface TraceService {

    /** Идентификатор окна «для всех пользователей». */
    String ALL_USERS = "*";

    /**
     * Включить трассировку для пользователя на N минут.
     *
     * @param userId  имя пользователя (не "*" и не null)
     * @param minutes длительность окна
     */
    void startTrace(String userId, int minutes);

    /** Включить трассировку для всех пользователей на N минут. */
    void startTraceForAll(int minutes);

    /** Выключить трассировку для пользователя (и «всех», если был задан "*"). */
    void stopTrace(String userId);

    /** Выключить все активные окна. */
    void stopAllTraces();

    /** true — для данного пользователя активно окно трассировки (или глобальное). */
    boolean isTraceActive(String userId);

    /** Активные окна (истёкшие исключаются). */
    List<TraceWindow> activeTraces();

    record TraceWindow(String userId, Instant traceUntil) {
    }
}