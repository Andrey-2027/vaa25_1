package org.ipro.report.ui;

import com.vaadin.flow.component.UI;

/**
 * Optional application hook for applying saved UI preferences to a report route.
 * The report module does not know the application preference store.
 */
public interface ReportUiPreferences {

    void applySavedDensity(UI ui);
}
