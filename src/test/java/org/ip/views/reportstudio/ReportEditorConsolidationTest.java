package org.ip.views.reportstudio;

import com.vaadin.flow.component.Component;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D3.6.7: единственный production-стек редактора и сохранённый capability ledger.
 * Поведенческие операции покрываются обычным {@link ReportStructureEditorTest}.
 */
class ReportEditorConsolidationTest {

    private static final Path ROOT = Path.of("src/main/java/org/ip/views/reportstudio");

    /** Элементы управления, наличие которых было подтверждено сравнением с compact-стеком. */
    private static final List<String> ACCEPTED_CONTROLS = List.of(
            "bands", "bandGroup", "groupParent", "applyBand", "selectionHint", "bandHint", "bandFormWrap",
            "queryCombo", "addColumnButton", "addRowNumberButton", "addExpressionButton",
            "addFormulaButton", "addTextButton", "fieldsGrid", "fieldUp", "fieldDown", "fieldRemove",
            "palette", "paletteHint", "paletteKind", "paletteFieldQuery", "paletteCaption", "paletteWidth",
            "paletteAlignment", "paletteFormat", "paletteBorder", "paletteVisible", "paletteAggregation",
            "paletteTextButton", "startNewPage", "sortCombo", "sortDirection", "addSortButton", "sortGrid",
            "sortUp", "sortDown", "sortRemove", "sortHint", "fieldHint", "errorHint", "flowLane",
            "gridEnabled", "stripeRows", "baseFontSize", "pageSize", "pageOrientation",
            "groupTitleWidth", "groupHeaderLayout", "addAggregation", "noDataEnabled", "noDataText",
            "availableGrid", "availableFilter", "availableList");

    @Test
    void productionContainsOnlyTheCanonicalEditorRoles() throws IOException {
        Set<String> roles = new LinkedHashSet<>();
        Set<String> variantFiles = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = ROOT.relativize(file).toString().replace('\\', '/');
                String name = file.getFileName().toString();
                if (name.matches("Report(CatalogView|EditorView|StructureEditor|ParamEditor)(Compact|Structured)?\\.java")) {
                    roles.add(relative);
                }
                if (relative.contains("/compact/") || relative.contains("/structured/")
                        || name.endsWith("Compact.java") || name.endsWith("Structured.java")) {
                    variantFiles.add(relative);
                }
            }
        }

        assertThat(roles).containsExactlyInAnyOrder(
                "ReportCatalogView.java",
                "ReportEditorView.java",
                "ReportParamEditor.java",
                "ReportStructureEditor.java");
        assertThat(variantFiles)
                .as("D3.6.7 удаляет variant production-код после переноса URL на aliases")
                .isEmpty();
    }

    @Test
    void canonicalStructureEditorRendersEveryAcceptedControl() throws Exception {
        ReportStructureEditor editor = new ReportStructureEditor();
        Set<Component> tree = descendants(editor);

        for (String name : ACCEPTED_CONTROLS) {
            Field field = ReportStructureEditor.class.getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(editor);
            assertThat(value)
                    .as("принятый контроль '%s' обязан остаться в каноническом редакторе", name)
                    .isInstanceOf(Component.class);
            assertThat(tree)
                    .as("принятый контроль '%s' должен быть подключён к экрану", name)
                    .contains((Component) value);
        }

        assertThat(ACCEPTED_CONTROLS).hasSizeGreaterThan(45);
    }

    private static Set<Component> descendants(Component root) {
        Set<Component> found = new LinkedHashSet<>();
        collect(root, found);
        return found;
    }

    private static void collect(Component component, Set<Component> found) {
        if (!found.add(component)) {
            return;
        }
        component.getChildren().forEach(child -> collect(child, found));
    }
}
