package org.ip.config;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.bstek.ureport.definition.datasource.JpqlDatasetExecutor;

/**
 * Мостик UReport -> JPQL: выполнение JPQL через единый сервис
 * {@link JpqlRunService} (guard -> preview -> RLS), общий для всех движков
 * отчётов (см. ReportJR-Jpql-Plan.md, Фаза 1).
 *
 * Два способа использования в дизайнере UReport:
 *
 * 1. (B2, рекомендуемый) Обычный SQL-датасет на любом источнике, текст
 *    в поле SQL начинается с маркера "jpql:":
 *        jpql:
 *        select a.id as id, a.codeSpec as codeSpec from PrdSpec a
 *    Параметры датасета (таблица параметров в диалоге) передаются в JPQL
 *    как named-биндинги (имя параметра = имя :param).
 *    Выполняется через {@link #execute(String, Map)}.
 *
 * 2. (B1, legacy) Bean-датасет: Bean ID = ureportJpqlDataset, метод dataset,
 *    текст JPQL в поле "Имя датасета". Выполняется через {@link #dataset}.
 */
@Component("ureportJpqlDataset")
public class UReportJpqlDataset implements JpqlDatasetExecutor {

    private final JpqlRunService jpqlRunService;

    public UReportJpqlDataset(JpqlRunService jpqlRunService) {
        this.jpqlRunService = jpqlRunService;
    }

    /**
     * Точка вызова движком UReport для bean-датасета (вариант B1,
     * сигнатура фиксирована BeanDatasetDefinition): текст JPQL передаётся
     * в поле "Имя датасета".
     */
    public List<Map<String, Object>> dataset(String datasourceName, String datasetName,
                                             Map<String, Object> parameters) {
        String jpql = resolveJpqlFromName(datasetName);
        return jpqlRunService.runAsMaps(jpql, parameters);
    }

    /**
     * Точка вызова для SQL-датасета с маркером "jpql:" (вариант B2).
     */
    @Override
    public List<Map<String, Object>> execute(String jpql, Map<String, Object> parameters) {
        return jpqlRunService.runAsMaps(jpql, parameters);
    }

    private String resolveJpqlFromName(String datasetName) {
        if (datasetName == null || datasetName.isBlank()) {
            throw new IllegalArgumentException(
                    "Имя датасета должно содержать JPQL-запрос, начинающийся с \"select\"");
        }
        String jpql = datasetName.strip();
        if (!jpql.toLowerCase().startsWith("select")) {
            throw new IllegalArgumentException(
                    "Ожидается JPQL-запрос (текст в поле \"Имя датасета\" должен начинаться с \"select\")");
        }
        return jpql;
    }
}
