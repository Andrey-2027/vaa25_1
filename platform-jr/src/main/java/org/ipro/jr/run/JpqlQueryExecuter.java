package org.ipro.jr.run;

import net.sf.jasperreports.engine.JRDataSource;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.query.JRQueryExecuter;

/**
 * Регистрационный executer языка {@code jpql} для JasperReports.
 *
 * <p>В приложении данные главного датасета всегда передаются программно
 * ({@code REPORT_DATA_SOURCE} из {@link JrxmlExecutionService}), поэтому сам
 * executer данных не производит: он существует, чтобы JR принимал язык
 * {@code jpql} при дизайне/компиляции шаблона (иначе setQuery/compile падает
 * с "No query executer factory registered").</p>
 */
public class JpqlQueryExecuter implements JRQueryExecuter {

    @Override
    public JRDataSource createDatasource() throws JRException {
        throw new JRException(
                "Данные jpql-запроса передаются через REPORT_DATA_SOURCE "
                        + "(конвейер guard+RLS), прямое выполнение из шаблона запрещено");
    }

    @Override
    public void close() {
        // не держит ресурсов
    }

    @Override
    public boolean cancelQuery() {
        return false;
    }
}
