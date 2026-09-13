package org.ip.service;

import jakarta.persistence.EntityManager;
import org.ip.model.GroupNom;
import org.ip.repository.GroupNomRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Регрессия: сохранение сущности с {@code version = null} при {@code id != null}.
 *
 * <p>Колонка version появилась в таблицах через {@code ddl-auto=update} уже после
 * заполнения данных — «старые» строки имеют {@code version = NULL}. При сохранении
 * такой (detached) сущности Hibernate в {@code isTransient()} видит противоречие
 * «версия говорит „новая", id говорит „сохранённая"» и бросает
 * {@code PropertyValueException: Detached entity with generated id '1' has an
 * uninitialized version value 'null'} (оборачивается Spring в
 * {@code DataIntegrityViolationException}). {@link AbstractBaseService} нормализует
 * null-версию в 0 перед записью — этот тест фиксирует поведение.</p>
 */
@DataJpaTest
@EnableJpaRepositories(basePackages = "org.ip")
class GroupNomServiceVersionTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private GroupNomRepository groupNomRepository;

    private GroupNomService newService() {
        GroupNomService service = new GroupNomService(groupNomRepository,
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        // Поля, не участвующие в save() GroupNom, не нужны; numberingService —
        // Optional-бин, без инъекции был бы null → NPE в assignNumbers(). RLS
        // write-guard больше не живёт в сервисе (C3.0.1), поэтому accessService
        // сервису не нужен.
        ReflectionTestUtils.setField(service, "numberingService", Optional.empty());
        return service;
    }

    @Test
    void saveDetachedEntityWithNullVersionSucceeds() {
        GroupNomService service = newService();

        // Обычный путь: новая запись создаётся и получает version = 0.
        GroupNom created = service.save(new GroupNom("T-VER", "Тест версии"));
        assertThat(created.getId()).isNotNull();
        assertThat(created.getVersion()).isEqualTo(0L);

        // Имитация «legacy»-строки: version = null при id != null.
        // Делаем сущность detached и «портим» версию, как если бы она
        // прочиталась из строки БД, заполненной до добавления @Version.
        entityManager.flush();
        entityManager.clear();
        GroupNom detached = groupNomRepository.findById(created.getId()).orElseThrow();
        detached.setVersion(null);
        detached.setName("Тест версии (изменено)");

        // До исправления здесь падало PropertyValueException
        // «Detached entity ... uninitialized version value».
        GroupNom saved = service.save(detached);

        assertThat(saved.getVersion()).isEqualTo(0L);
        assertThat(groupNomRepository.findById(saved.getId()).orElseThrow().getName())
                .isEqualTo("Тест версии (изменено)");
    }
}