package org.ip.service;

import jakarta.persistence.EntityManager;
import org.ip.model.AttributeValue;
import org.ip.repository.AttributeValueRepository;
import org.ipro.data.CanonicalEntityService;
import org.ipro.crud.ValidationException;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Канонический handle для слайс-тестов {@code @DataJpaTest}: заглушка, исполняющая ровно то
 * обещание, на которое опирается сервис значений, — «создание и именованная мутация строки
 * остаются в persistence context слайса».
 *
 * <p>Заглушка появилась вместе с переводом пяти прямых записей {@code AttributeValueService} на
 * canonical path: доменные правила переименования и дедупа проверяются здесь, а сам seam
 * (решение об операции, порядок до загрузки строки, фиксация авторизации, hooks и события)
 * проверяется полным контекстом — {@code AttributeValueRenameOperationIT}. Смешивать эти два
 * уровня в одном тесте значило бы либо поднимать полный контекст ради нормализации числа, либо
 * считать заглушку доказательством работы границы.</p>
 *
 * <p>Заглушка не решает прав и не подменяет решение C5: она не разрешает то, что запрещено, —
 * её просто нет в контуре без RLS и C5. Управляемая строка читается тем же
 * {@code findByIdForUpdate}, что и в production-пути: так слайс-тест проверяет, что доменные
 * правила накладываются на строку под блокировкой, а не на её копию.</p>
 */
final class AttributeValueCanonicalStub {

    private AttributeValueCanonicalStub() {
    }

    /** Handle, у которого {@code create} персистит, а именованная операция применяет мутацию. */
    @SuppressWarnings("unchecked")
    static CanonicalEntityService<AttributeValue> attributeValues(
            EntityManager entityManager, AttributeValueRepository repository) {
        CanonicalEntityService<AttributeValue> canonical =
            (CanonicalEntityService<AttributeValue>) mock(CanonicalEntityService.class);
        when(canonical.create(any(AttributeValue.class))).thenAnswer(invocation -> {
            AttributeValue entity = invocation.getArgument(0);
            entityManager.persist(entity);
            return entity;
        });
        when(canonical.executeNamed(anyString(), any(), any(Consumer.class)))
            .thenAnswer(invocation -> {
                Long id = ((Number) invocation.getArgument(1)).longValue();
                @SuppressWarnings("unchecked")
                Consumer<AttributeValue> mutation = invocation.getArgument(2);
                AttributeValue value = repository.findByIdForUpdate(id)
                    .orElseThrow(() -> new ValidationException(
                        "Значение атрибута с id=" + id + " не найдено."));
                mutation.accept(value);
                return value;
            });
        return canonical;
    }
}
