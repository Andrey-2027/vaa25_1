package org.ipro.jr;

import net.sf.jasperreports.engine.design.JRDesignBand;
import net.sf.jasperreports.engine.design.JRDesignExpression;
import net.sf.jasperreports.engine.design.JRDesignField;
import net.sf.jasperreports.engine.design.JRDesignParameter;
import net.sf.jasperreports.engine.design.JRDesignQuery;
import net.sf.jasperreports.engine.design.JRDesignSection;
import net.sf.jasperreports.engine.design.JRDesignStaticText;
import net.sf.jasperreports.engine.design.JRDesignTextField;
import net.sf.jasperreports.engine.design.JasperDesign;
import net.sf.jasperreports.engine.xml.JRXmlWriter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Временный зонд: печатает канонический JR7-формат jrxml (JRXmlWriter),
 * чтобы зафиксировать эталонный шаблон для фикстур тестов.
 */
class JrxmlFormatProbeTest {

    @Test
    void printExtensions() {
        var bundles = net.sf.jasperreports.extensions.ExtensionsEnvironment
                .getExtensionsRegistry()
                .getExtensions(net.sf.jasperreports.engine.query.JRQueryExecuterFactoryBundle.class);
        System.out.println("PROBE bundles=" + bundles.size());
        for (var b : bundles) {
            try {
                System.out.println("PROBE langs=" + java.util.Arrays.toString(b.getLanguages()));
            } catch (Throwable t) {
                System.out.println("PROBE err=" + t);
            }
        }
    }

    @Test
    void printCanonicalJr7Xml() throws Exception {
        JasperDesign design = new JasperDesign();
        design.setName("journals");
        design.setPageWidth(595);
        design.setPageHeight(842);
        design.setColumnWidth(555);
        design.setLeftMargin(20);
        design.setRightMargin(20);
        design.setTopMargin(20);
        design.setBottomMargin(20);

        JRDesignQuery query = new JRDesignQuery();
        query.setText("jpql:\nselect j.id as id, j.name as nm from Journal j where j.code = :code");
        query.setLanguage("jpql");
        design.setQuery(query);

        JRDesignParameter code = new JRDesignParameter();
        code.setName("code");
        code.setValueClassName("java.lang.String");
        design.addParameter(code);

        JRDesignField id = new JRDesignField();
        id.setName("id");
        id.setValueClassName("java.lang.Long");
        design.addField(id);

        JRDesignField nm = new JRDesignField();
        nm.setName("nm");
        nm.setValueClassName("java.lang.String");
        design.addField(nm);

        JRDesignBand title = new JRDesignBand();
        title.setHeight(30);
        JRDesignStaticText staticText = new JRDesignStaticText();
        staticText.setX(0);
        staticText.setY(0);
        staticText.setWidth(555);
        staticText.setHeight(25);
        staticText.setText("Журналы");
        title.addElement(staticText);
        design.setTitle(title);
        ((JRDesignSection) design.getDetailSection()).addBand(new JRDesignBand());

        JRDesignBand detail = new JRDesignBand();
        detail.setHeight(20);
        JRDesignTextField idField = new JRDesignTextField();
        idField.setX(0);
        idField.setY(0);
        idField.setWidth(100);
        idField.setHeight(20);
        idField.setExpression(new JRDesignExpression("$F{id}"));
        detail.addElement(idField);
        JRDesignTextField nmField = new JRDesignTextField();
        nmField.setX(110);
        nmField.setY(0);
        nmField.setWidth(400);
        nmField.setHeight(20);
        nmField.setExpression(new JRDesignExpression("$F{nm}"));
        detail.addElement(nmField);
        ((JRDesignSection) design.getDetailSection()).addBand(detail);

        String xml = JRXmlWriter.writeReport(design, "UTF-8");
        Path out = Path.of("target", "jr7-canonical.jrxml");
        Files.writeString(out, xml);
        System.out.println("PROBE written: " + out.toAbsolutePath());
    }
}
