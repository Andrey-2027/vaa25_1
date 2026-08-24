package org.ipro.form.config;

import org.ipro.form.SelectionFormAssembler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Бины формообразующего слоя платформы (org.ipro.form).
 *
 * <p>Регистрация через {@code @Import}: платформа исключена из component-scan
 * приложения, при этом сохраняется полная аннотационная обработка класса.
 * По мере переноса UI-слоя в платформу (см.
 * docs/plans/reportstudio-reverse-deps-plan.md) список импортов растёт.</p>
 */
@AutoConfiguration
@Import({SelectionFormAssembler.class})
public class FormAutoConfiguration {
}
