package org.ip.config;

import org.ip.views.query.FormEntitySelection;
import org.ip.views.query.QueryWorkbenchAccess;
import org.ipro.crud.EntityLookup;
import org.ipro.form.SelectionFormAssembler;
import org.ipro.navigation.NavigationAdministrationAccess;
import org.ipro.query.QueryReadRefusedException;
import org.ipro.query.list.QueryListAdminAccess;
import org.ipro.query.ui.grid.QueryTaskRunner;
import org.ipro.query.ui.selection.EntitySelection;
import org.ipro.report.task.ReportTaskExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.RejectedExecutionException;

@Configuration(proxyBeanMethods = false)
class ApplicationQueryListConfiguration {
    @Bean
    QueryListAdminAccess queryListAdminAccess(NavigationAdministrationAccess menuAccess) {
        return menuAccess::isAdministrator;
    }

    @Bean
    EntitySelection applicationQueryEntitySelection(SelectionFormAssembler assembler, EntityLookup lookup) {
        return new FormEntitySelection(assembler, lookup);
    }

    @Bean
    QueryTaskRunner queryTaskRunner(ReportTaskExecutor tasks) {
        return QueryTaskRunner.of(work -> {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (!QueryWorkbenchAccess.allows(authentication)) {
                throw new QueryReadRefusedException(
                        QueryReadRefusedException.Reason.ACCESS_DENIED, "Требуется вход для выполнения запроса");
            }
            try {
                tasks.execute(authentication, null, work);
            } catch (RejectedExecutionException busy) {
                throw new QueryReadRefusedException(
                        QueryReadRefusedException.Reason.QUOTA_BUSY, "Очередь выполнения запросов заполнена");
            }
        });
    }
}
