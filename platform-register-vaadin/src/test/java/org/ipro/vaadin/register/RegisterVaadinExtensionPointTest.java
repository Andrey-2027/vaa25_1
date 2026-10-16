package org.ipro.vaadin.register;

import org.ipro.data.EntityDataAccess;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.data.EntityReadAccess;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.form.link.FormLinkService;
import org.ipro.register.RecorderTypeRegistry;
import org.ipro.register.RegisterKeyChangePermissions;
import org.ipro.register.RegisterKeyChanges;
import org.ipro.register.RegisterReaderFactory;
import org.ipro.register.RegisterRegistry;
import org.ipro.vaadin.register.config.RegisterVaadinConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * D-R S4 — точка расширения стандартного интерфейса регистра: она не «объявлена в javadoc»,
 * а проверяется запуском контейнера.
 *
 * <p>Срез разделил общее и прикладное так: в артефакт уехал стандартный адаптер форм
 * ({@link RegisterFormCustomization}), а точкой расширения остался механизм платформы —
 * бин адаптера и композиция {@code FormTypeCustomization} слоя форм. Разделение без гейта
 * было бы обещанием: «потребитель может заменить адаптер» легко переживает удаление
 * {@code @ConditionalOnMissingBean} и никто бы этого не заметил. Поэтому три свойства
 * проверяются здесь, на конфигурации артефакта, а не в тесте потребителя:</p>
 *
 * <ol>
 * <li><b>Умолчание.</b> Потребитель, объявивший регистр и ничего больше, получает стандартный
 *     интерфейс — иначе «объявил регистр — получил формы» перестаёт быть правдой, и каждый
 *     потребитель пишет адаптер заново.</li>
 * <li><b>Отступление.</b> Свой бин адаптера вытесняет стандартный: у потребителя, которому
 *     стандартные формы не подходят, остаётся один бин, а не конфликт двух определений.
 *     Проверяется тождеством объекта: «тот же бин» — это и есть отступление, тогда как
 *     «бин есть» одинаково верно и когда платформенный выиграл.</li>
 * <li><b>Односторонность.</b> Без движка интерфейс не собирается вовсе. Это негативный гейт
 *     среза: модуль интерфейса не объявляет ни реестра, ни фабрики читателя, поэтому вернуть
 *     вторую сборку читателя рядом с фабрикой S1 невозможно — контекст падает с отсутствующим
 *     движком вместо того, чтобы тихо собрать свой.</li>
 * </ol>
 *
 * <p>Движок в тестах — заглушки: интерфейсу нужны порты, а не их реализация, и подстановка
 * настоящих соседей сделала бы гейт проверкой соседей, а не точки расширения. Комментариями
 * здесь ничего не заглушается: {@code withBean} кладёт определение раньше авто-конфигураций,
 * поэтому порядок применения контейнера — тот же, что у потребителя.</p>
 */
class RegisterVaadinExtensionPointTest {

    /** Свой адаптер потребителя: тот же тип, поэтому вытеснение проверяется тождеством. */
    private final RegisterFormCustomization consumerAdapter = mock(RegisterFormCustomization.class);

    private final ApplicationContextRunner interfaceOnly = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RegisterVaadinConfiguration.class))
        .withBean(EntityReadAccess.class, () -> mock(EntityReadAccess.class))
        .withBean(EntityDataAccessResolver.class, () -> mock(EntityDataAccessResolver.class))
        .withBean(InstanceNameResolver.class, () -> mock(InstanceNameResolver.class))
        .withBean(FormLinkService.class, () -> mock(FormLinkService.class))
        .withBean(RecorderTypeRegistry.class, () -> mock(RecorderTypeRegistry.class))
        .withBean(EntityDataAccess.class, () -> mock(EntityDataAccess.class))
        .withBean(RegisterReaderFactory.class, () -> mock(RegisterReaderFactory.class))
        .withBean(RegisterKeyChanges.class, () -> mock(RegisterKeyChanges.class))
        .withBean(RegisterKeyChangePermissions.class, () -> mock(RegisterKeyChangePermissions.class))
        .withBean(RegisterRegistry.class, () -> mock(RegisterRegistry.class));

    @Test
    void standardInterfaceArrivesWithoutTheConsumerWritingAnything() {
        interfaceOnly.run(context -> {
            assertThat(context).hasSingleBean(RegisterFormCustomization.class);
            assertThat(context.getBean(RegisterFormCustomization.class).getClass())
                .as("потребитель, объявивший регистр, получает именно стандартный адаптер:"
                    + " подкласс означал бы, что артефакт приносит что-то другое, чем обещает")
                .isEqualTo(RegisterFormCustomization.class);
            assertThat(context)
                .as("показ регистратора приезжает тем же артефактом: без резолвера представлений"
                    + " стандартный адаптер не собрался бы вовсе")
                .hasSingleBean(RecorderRefPresentationResolver.class);
        });
    }

    @Test
    void consumerAdapterTakesTheStandardOnesPlace() {
        interfaceOnly.withBean(RegisterFormCustomization.class, () -> consumerAdapter)
            .run(context -> {
                assertThat(context)
                    .as("вытеснение оставляет ровно один бин: два определения одного типа —"
                        + " это конфликт на старте, а не расширение")
                    .hasSingleBean(RegisterFormCustomization.class);
                assertThat(context.getBean(RegisterFormCustomization.class))
                    .as("@ConditionalOnMissingBean отступает перед бин потребителя: иначе"
                        + " расширить стандартные формы было бы нечем, и точка расширения"
                        + " существовала бы только в описании среза")
                    .isSameAs(consumerAdapter);
            });
    }

    private static List<String> causeMessages(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null) {
                messages.add(cause.getMessage());
            }
        }
        return messages;
    }

    @Test
    void interfaceModuleDoesNotAssembleTheEngineItself() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RegisterVaadinConfiguration.class))
            .withBean(EntityReadAccess.class, () -> mock(EntityReadAccess.class))
            .withBean(EntityDataAccessResolver.class, () -> mock(EntityDataAccessResolver.class))
            .withBean(InstanceNameResolver.class, () -> mock(InstanceNameResolver.class))
            .withBean(FormLinkService.class, () -> mock(FormLinkService.class))
            .withBean(RecorderTypeRegistry.class, () -> mock(RecorderTypeRegistry.class))
            .withBean(EntityDataAccess.class, () -> mock(EntityDataAccess.class))
            .withBean(RegisterReaderFactory.class, () -> mock(RegisterReaderFactory.class))
            .withBean(RegisterKeyChanges.class, () -> mock(RegisterKeyChanges.class))
            .withBean(RegisterKeyChangePermissions.class, () -> mock(RegisterKeyChangePermissions.class))
            .run(context -> {
                assertThat(context)
                    .as("без реестра деклараций интерфейс обязан упасть: он не владеет"
                        + " объявлением регистра, а берёт его у потребителя")
                    .hasFailed();
                assertThat(context.getStartupFailure())
                    .as("падение называет отсутствующий движок, а не молча собирает свой: S1 снял"
                        + " вторую сборку читателя у приложения, и негативный гейт среза следит,"
                        + " чтобы она не вернулась внутрь артефакта интерфейса")
                    .hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class);
                assertThat(causeMessages(context.getStartupFailure()))
                    .as("контейнер обязан пожаловаться именно на реестр деклараций: иначе"
                        + " негативный гейт проверял бы любое падение контекста")
                    .anySatisfy(message -> assertThat(message).contains("RegisterRegistry"));
            });
    }
}
