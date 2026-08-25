package org.ip.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.ip.config.JpqlRunService;
import org.ip.reports.ReportRights;
import org.ipro.rls.AccessService;
import org.ipro.reportstudio.data.QueryField;
import org.ipro.reportstudio.data.ReportDataset;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST-эндпоинт JPQL-превью для удалённых клиентов (Jaspersoft Studio,
 * фаза 3 плана ReportJR-Jpql-Plan.md).
 *
 * <p>Выполняет произвольный JPQL через единый конвейер
 * {@link JpqlRunService} (guard → preview → RLS) под учёткой вызвавшего
 * пользователя. Эндпоинт убирает естественный барьер «создай шаблон»,
 * поэтому доступ строго по CHECK_ONLY-праву
 * {@code REPORTS:JPQL_PREVIEW} (403 без гранта) — «аутентифицирован»
 * недостаточно.</p>
 *
 * <p>Транспорт — HTTP Basic (не cookie-Vaadin-сессия): только в этом режиме
 * отключение CSRF безопасно; цепочка безопасности выделена в SecurityConfig.</p>
 */
@RestController
@RequestMapping("/api/report-jpql")
public class ReportJpqlPreviewController {

    /** Измерение права на эндпоинт (см. {@link ReportRights#JpqlPreview}). */
    public static final String JPQL_PREVIEW_DIMENSION = "REPORTS:JPQL_PREVIEW";

    private final JpqlRunService jpqlRunService;
    private final AccessService accessService;

    public ReportJpqlPreviewController(JpqlRunService jpqlRunService,
                                       AccessService accessService) {
        this.jpqlRunService = jpqlRunService;
        this.accessService = accessService;
    }

    public record PreviewRequest(String jpql, Map<String, Object> params, Integer maxRows) {
    }

    public record ColumnDto(String name, String type) {
    }

    public record PreviewResponse(List<ColumnDto> columns,
                                  List<Map<String, Object>> rows,
                                  boolean truncated) {
    }

    @PostMapping("/preview")
    public ResponseEntity<?> preview(@RequestBody PreviewRequest request,
                                     Authentication authentication) {
        String username = authentication.getName();

        // 1) Право — до любых проверок запроса (Р5)
        if (!accessService.hasAnyAccess(JPQL_PREVIEW_DIMENSION, username)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Нет права " + JPQL_PREVIEW_DIMENSION));
        }
        if (request == null || request.jpql() == null || request.jpql().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "jpql обязателен"));
        }

        // 2) Выполнение через единый конвейер (guard + RLS + лимиты)
        int effectiveMaxRows = request.maxRows() == null || request.maxRows() <= 0
                ? JpqlRunService.MAX_ROWS
                : Math.min(request.maxRows(), JpqlRunService.MAX_ROWS);
        ReportDataset dataset;
        try {
            dataset = jpqlRunService.run(request.jpql(),
                    request.params() == null ? Map.of() : request.params());
        } catch (IllegalArgumentException | IllegalStateException e) {
            // отказ guard'а / ошибка запроса — человекочитаемо, не 500
            return ResponseEntity.badRequest().body(Map.of("error",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }

        return ResponseEntity.ok(toResponse(dataset, effectiveMaxRows));
    }

    static PreviewResponse toResponse(ReportDataset dataset, int maxRowsRequested) {
        QueryField[] fields = dataset.fields();
        List<ColumnDto> columns = new ArrayList<>(fields.length);
        for (QueryField field : fields) {
            columns.add(new ColumnDto(field.name(),
                    field.javaType() == null ? "Object" : field.javaType().getSimpleName()));
        }
        List<Map<String, Object>> rows = new ArrayList<>(dataset.rowCount());
        for (var row : dataset.rows()) {
            Map<String, Object> map = new LinkedHashMap<>(fields.length * 2);
            for (int i = 0; i < fields.length; i++) {
                map.put(fields[i].name(), JpqlRunService.displayValue(row.value(i)));
            }
            rows.add(map);
        }
        boolean truncated = rows.size() >= maxRowsRequested;
        return new PreviewResponse(columns, rows, truncated);
    }

    /** Метка времени формирования ответа (для отладки клиентов); не часть контракта. */
    public record DebugInfo(Instant at) {
    }
}
