package org.ipro.reportstudio.query;

/**
 * Семантический анализатор JPQL для отчётов (Фаза 2). Разбирает запрос без
 * выполнения: проверяет, что это SELECT, собирает сущности, к которым идёт
 * обращение (корни, явные/неявные джойны, подзапросы, CTE), колонки верхнего
 * SELECT и имена параметров. Реализация — поверх SQM Hibernate
 * (SqmQuerySemanticAnalyzer); SQM-типы за пределы интерфейса не выходят.
 */
public interface QuerySemanticAnalyzer {

    Analysis analyze(String jpql);
}