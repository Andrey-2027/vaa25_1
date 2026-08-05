package org.ipro.telemetry.core;

public final class MdcKeys {

    public static final String TRACE_ID = "traceId";
    public static final String USER = "user";
    public static final String SESSION = "session";
    public static final String OPERATION = "operation";
    public static final String ENTITY = "entity";
    public static final String ENTITY_ID = "entityId";
    /** Флаг «для этого запроса включена L2-трассировка» (значение "1"). */
    public static final String TRACE = "trace";

    private MdcKeys() {
    }
}