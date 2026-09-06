package org.ipro;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.ipro.form.builder.SelectionColumnResolver;
import org.ipro.form.registry.FormRegistry;
import org.ipro.form.registry.FormResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * П0 «Сборка читает только резолвнутые факты» (docs/platform-forms-rules.md §2/§5).
 *
 * <p>Слой сборки — {@code org.ipro.form.builtin}: ListForm/ItemForm/SelectionForm/ItemTable
 * строят дерево из готовых фактов. Всё вариантное ветвление выполняет слой резолюции
 * ({@code FormResolver}, {@code FormRegistry}, {@code SelectionColumnResolver}, вариантный
 * резолв в {@code FieldFactory}). Три механизма принуждения:</p>
 *
 * <ol>
 *   <li><b>Структурное правило:</b> builtin не зависит от machinery резолюции.
 *       Единственное исключение — {@code ItemTable}, который <i>пересылает</i> ключ варианта
 *       строки в {@code FormResolver#resolveItemForm} для вложенной формы (задокументированное
 *       поведение, интерпретации не выполняет); пересылка разрешена, трактовка — нет.</li>
 *   <li><b>Скан источников:</b> в builtin нет сырого идентификатора {@code variant}
 *       (кроме единственной точки пересылки в ItemTable) и нет интерпретации
 *       ({@code variant.equals(...)}, {@code switch (variant)}).</li>
 *   <li><b>Скан источников:</b> builtin не читает сырые строковые ключи параметров
 *       ({@code getParameter(...)}) — только резолвнутые факты ({@code ListFormContext},
 *       параметры, примененные {@code FormResolver} до сборки).</li>
 * </ol>
 *
 * <p>Публика {@code MetadataResolver} в builtin (ListForm: диалог видов, ItemTable: колонки
 * секций) правилом не запрещена: это доступ к метаданным (вход сборки), а не вариантное
 * ветвление. Публика {@code ContextFilterPanel}/{@code ContextFilterField} — потребление
 * резолвнутых фактов (эталонный пример §6 правил).</p>
 */
@AnalyzeClasses(packages = "org.ipro", importOptions = ImportOption.DoNotIncludeTests.class)
class FormAssemblyPurityTest {

    @ArchTest
    static final ArchRule assemblyDoesNotTouchResolutionMachinery =
            noClasses().that().resideInAPackage("org.ipro.form.builtin..")
                    .and().doNotHaveFullyQualifiedName("org.ipro.form.builtin.ItemTable")
                    .should().dependOnClassesThat(belongToAnyOf(
                            FormResolver.class, FormRegistry.class, SelectionColumnResolver.class))
                    .because("П0 (docs/platform-forms-rules.md §2): слой сборки читает только "
                            + "резолвнутые факты; вариантное ветвление — обязанность резолюции. "
                            + "Исключение: ItemTable пересылает ключ варианта строки в "
                            + "FormResolver.resolveItemForm (вложенная форма секции) без трактовки.");

    // === Скан источников ===

    private static final Path BUILTIN_DIR = Path.of("src/main/java/org/ipro/form/builtin");

    /** Сырой идентификатор варианта (слово целиком, lowerCamel; ButtonVariant не матчится). */
    private static final Pattern RAW_VARIANT = Pattern.compile("\\bvariant\\b");

    /** Интерпретация варианта = ветвление по его значению (canonical smell: "journal".equals(variant)). */
    private static final Pattern VARIANT_INTERPRETATION = Pattern.compile(
            "variant\\.equals\\(|\\.equals\\(\\s*variant\\s*\\)|switch\\s*\\(\\s*variant\\s*\\)");

    /** Сырые строковые ключи параметров —Reading их сборкой запрещен: это дело резолюции. */
    private static final Pattern RAW_PARAMETER_KEYS = Pattern.compile("getParameter\\(|getParameters\\(");

    @Test
    void builtinUsesNoRawVariantIdentifierOutsideTheSingleForwardingPoint() throws IOException {
        assertThat(BUILTIN_DIR).as("builtin directory resolved from module root").exists();

        try (Stream<Path> files = Files.walk(BUILTIN_DIR)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                String name = file.getFileName().toString();
                String source = read(file);

                if ("ItemTable.java".equals(name)) {
                    // Единственная разрешенная точка пересылки варианта: если роль изменилась —
                    // обнови allow-list здесь и в структурном правиле выше.
                    assertThat(source)
                            .as("ItemTable.java: allow-list действителен — пересылка варианта "
                                    + "в FormResolver.resolveItemForm на месте")
                            .contains("import org.ipro.form.registry.FormResolver;")
                            .contains("formResolverSupplier.get().resolveItemForm(");
                } else {
                    assertThat(RAW_VARIANT.matcher(source).find())
                            .as("%s: сырой идентификатор 'variant' в слое сборки — ветвление "
                                    + "по варианту должно жить в резолюции (П0)", name)
                            .isFalse();
                }
                assertThat(VARIANT_INTERPRETATION.matcher(source).find())
                        .as("%s: интерпретация значения варианта внутри сборки запрещена (П0) "
                                + "— пересылай ключ в резолюцию", name)
                        .isFalse();
            });
        }
    }

    @Test
    void builtinReadsNoRawParameterKeys() throws IOException {
        assertThat(BUILTIN_DIR).as("builtin directory resolved from module root").exists();

        try (Stream<Path> files = Files.walk(BUILTIN_DIR)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                String name = file.getFileName().toString();
                String source = read(file);
                assertThat(RAW_PARAMETER_KEYS.matcher(source).find())
                        .as("%s: чтение сырых строковых ключей параметров в сборке — сборка "
                                + "получает уже примененные резолюцией факты (П0)", name)
                        .isFalse();
            });
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file, e);
        }
    }
}
