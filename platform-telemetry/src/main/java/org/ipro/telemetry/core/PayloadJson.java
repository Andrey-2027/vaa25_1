package org.ipro.telemetry.core;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Сборка payload_json событий телеметрии через Jackson (единый механизм
 * с TreeJsonRenderer). При ошибке сериализации возвращает безопасную
 * строку — payload никогда не должен ронять запись события.
 */
public final class PayloadJson {

    private static final ObjectMapper mapper = new ObjectMapper();

    private PayloadJson() {
    }

    public static String json(Map<String, Object> values) {
        try {
            return mapper.writeValueAsString(values);
        } catch (Exception e) {
            return "{\"payloadError\":true}";
        }
    }
}