package org.ipro.jr.run;

import net.sf.jasperreports.engine.JRDataset;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperReportsContext;
import net.sf.jasperreports.engine.JRValueParameter;
import net.sf.jasperreports.engine.query.AbstractQueryExecuterFactory;
import net.sf.jasperreports.engine.query.JRQueryExecuter;

import java.util.Map;

/**
 * Фабрика языка {@code jpql} для JasperReports (extension-point
 * {@code jasperreports_extension.properties}). Тот же язык регистрируется и в
 * extension-jar для Jaspersoft Studio (фаза 4 плана) — текст шаблона один и тот же.
 */
public class JpqlQueryExecuterFactory extends AbstractQueryExecuterFactory {

    @Override
    public Object[] getBuiltinParameters() {
        return new Object[0];
    }

    @Override
    public JRQueryExecuter createQueryExecuter(JasperReportsContext context, JRDataset dataset,
            Map<String, ? extends JRValueParameter> parameters) throws JRException {
        return new JpqlQueryExecuter();
    }

    @Override
    public boolean supportsQueryParameterType(String className) {
        return true;
    }
}
