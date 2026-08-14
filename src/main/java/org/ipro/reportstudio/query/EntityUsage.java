package org.ipro.reportstudio.query;

/**
 * Доступ к сущности внутри JPQL-запроса (Фаза 2). Собирается анализатором:
 * корни FROM, явные и неявные джойны, а также сущности из вложенных
 * подзапросов (inSubquery). entityName — имя сущности Hibernate
 * (getHibernateEntityName), path — путь доступа ("d", "d.journal",
 * "s.unit"), по которому сущность участвует в запросе.
 */
public record EntityUsage(String entityName, String path, boolean inSubquery) {

    @Override
    public String toString() {
        return entityName + (inSubquery ? " (subquery)" : "") + " @" + path;
    }
}