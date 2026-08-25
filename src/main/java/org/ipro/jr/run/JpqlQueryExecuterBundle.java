package org.ipro.jr.run;

import java.util.Arrays;
import java.util.List;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.query.JRQueryExecuterFactoryBundle;
import net.sf.jasperreports.engine.query.QueryExecuterFactory;

/**
 * Бандл языка {@code jpql} для реестра расширений JasperReports 7
 * (в core больше не регистрируется ни одного executer'а автоматически).
 */
public class JpqlQueryExecuterBundle implements JRQueryExecuterFactoryBundle {

    private final QueryExecuterFactory factory = new JpqlQueryExecuterFactory();

    @Override
    public String[] getLanguages() {
        return new String[] {"jpql"};
    }

    @Override
    public QueryExecuterFactory getQueryExecuterFactory(String language) throws JRException {
        if ("jpql".equals(language)) {
            return factory;
        }
        throw new JRException("Неизвестный язык запросов: " + language
                + " (поддерживаются " + Arrays.toString(getLanguages()) + ")");
    }

    /** Реестр расширений, отдающий бандл по типу {@link JRQueryExecuterFactoryBundle}. */
    public static class Registry
            implements net.sf.jasperreports.extensions.ExtensionsRegistry {

        @Override
        @SuppressWarnings("unchecked")
        public <T> List<T> getExtensions(Class<T> extensionType) {
            if (extensionType == JRQueryExecuterFactoryBundle.class) {
                return List.of((T) new JpqlQueryExecuterBundle());
            }
            return List.of();
        }
    }

    /** Фабрика реестра — класс, указываемый в jasperreports_extension.properties. */
    public static class RegistryFactory
            implements net.sf.jasperreports.extensions.ExtensionsRegistryFactory {

        @Override
        public net.sf.jasperreports.extensions.ExtensionsRegistry createRegistry(
                String registryId, net.sf.jasperreports.engine.JRPropertiesMap properties) {
            return new Registry();
        }
    }
}
