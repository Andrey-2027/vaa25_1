package org.ipro.utr;

import org.ipro.query.QueryProjectionCompiler;
import org.ipro.query.config.QueryAutoConfiguration;
import org.ipro.rls.RlsCurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

@AutoConfiguration(after = QueryAutoConfiguration.class)
@ConditionalOnProperty(name = "app.utr.enabled", havingValue = "true")
public class UtrAutoConfiguration {
    @Bean
    UtrFileStore utrFileStore(@Value("${app.utr.file-store-dir:./data/utr}") String directory,
                              RlsCurrentUser users, QueryProjectionCompiler compiler) {
        return new UtrFileStore(Path.of(directory), users, compiler);
    }
}
