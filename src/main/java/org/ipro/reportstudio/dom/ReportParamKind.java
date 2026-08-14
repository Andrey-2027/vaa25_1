package org.ipro.reportstudio.dom;

/**
 * Вид значения параметра отчёта (Фаза 3).
 * SCALAR — скалярные типы (String, числовые, даты, enum);
 * ENTITY — одиночная сущность (биндится свежий инстанс, перезапрошенный по Id);
 * ENTITY_LIST — список сущностей (контекстный запуск из грида = список из одного);
 * PERIOD — период: одно имя в модели, биндятся два имени :nameFrom/:nameTo.
 */
public enum ReportParamKind {

    SCALAR,
    ENTITY,
    ENTITY_LIST,
    PERIOD
}