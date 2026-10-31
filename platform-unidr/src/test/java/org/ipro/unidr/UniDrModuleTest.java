package org.ipro.unidr;

import org.ipro.unidr.model.UniDrDefinitionJsonCodec;
import org.ipro.unidr.model.UniDrDefinitionValidator;
import org.ipro.unidr.run.UniDrAccess;
import org.ipro.unidr.source.UniDrSourceCatalog;
import org.ipro.unidr.store.UniDrFileStore;
import org.ipro.rls.RlsCurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** M2.2.1: backend works against a synthetic source contract, without org.ip. */
class UniDrModuleTest {

    private final UniDrSourceCatalog catalog = UniDrTestSource.catalog();
    private final UniDrDefinitionJsonCodec codec =
        new UniDrDefinitionJsonCodec(new UniDrDefinitionValidator(catalog));

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken("tester", null, java.util.List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void syntheticDefinitionValidatesAndRoundTrips() {
        var definition = UniDrTestSource.recipe();
        assertThat(new UniDrDefinitionValidator(catalog).validate(definition)).isEmpty();
        assertThat(codec.decode(codec.encode(definition))).isEqualTo(definition);
    }

    @Test
    void fileStoreKeepsTheDefinitionWithoutApplicationTypes(@TempDir Path directory) {
        RlsCurrentUser current = () -> "tester";
        var store = new UniDrFileStore(directory, codec, new UniDrAccess(() -> true, current));
        var saved = store.create(UniDrTestSource.recipe(), "Проверка модуля");
        var loaded = store.load(saved.id());
        assertThat(loaded.definition()).isEqualTo(saved.definition());
        assertThat(loaded.author()).isEqualTo("tester");
    }
}
