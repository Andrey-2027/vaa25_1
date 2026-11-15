package org.ipro.query.config;

import org.ipro.query.QueryProjectionCompiler;
import org.ipro.query.list.QueryListAdminAccess;
import org.ipro.query.list.QueryListDeclaration;
import org.ipro.query.list.QueryListRegistry;
import org.ipro.query.list.QueryListRepository;
import org.ipro.rls.RlsCurrentUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;

@AutoConfiguration(after = QueryAutoConfiguration.class)
@ConditionalOnBean(QueryListAdminAccess.class)
@EntityScan("org.ipro.query.list")
@EnableJpaRepositories("org.ipro.query.list")
public class QueryListAutoConfiguration {
    @Bean
    QueryListRegistry queryListRegistry(QueryListRepository repository, QueryListAdminAccess access, RlsCurrentUser users,
            QueryProjectionCompiler compiler, ObjectProvider<QueryListDeclaration> declarations,
            PlatformTransactionManager transactions) {
        return new QueryListRegistry(repository, access, users, compiler, declarations.orderedStream().toList(), transactions);
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> queryListStartupCheck(QueryListRegistry registry) {
        return event -> registry.initialize();
    }
}
