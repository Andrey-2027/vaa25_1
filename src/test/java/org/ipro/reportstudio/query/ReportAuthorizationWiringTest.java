package org.ipro.reportstudio.query;

import org.ipro.query.*;
import org.ipro.query.param.*;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Prevent production factories from accidentally wiring a structural-only C5 adapter. */
class ReportAuthorizationWiringTest {
    @Test
    void productionUsesOnlyTheProtectedReportConstructors() {
        var requiredParameters = Map.of(ReportQueryGuard.class.getName(), 6,
            ReportQueryExecutor.class.getName(), 5);
        var production = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
            // M1.2: UI запроса переехал в platform-query-vaadin — забор обязан сканировать
            // и новый пакет, иначе переезд молча вывел бы код из-под проверки.
            .importPackages("org.ip", "org.ipro.reportstudio", "org.ipro.query.ui");
        var unsafeCalls = production.stream()
            // Deprecated constructors delegate internally to each other for compatibility.
            .filter(type -> !requiredParameters.containsKey(type.getName()))
            .flatMap(type -> type.getConstructorCallsFromSelf().stream())
            .filter(call -> requiredParameters.containsKey(call.getTargetOwner().getName()))
            .filter(call -> call.getTarget().getRawParameterTypes().size()
                < requiredParameters.get(call.getTargetOwner().getName()))
            .map(Object::toString).toList();
        assertThat(unsafeCalls).isEmpty();
    }
}
