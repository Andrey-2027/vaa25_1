package org.ipro.vaadin.register.config;

import org.ipro.data.EntityDataAccess;
import org.ipro.data.EntityDataAccessResolver;
import org.ipro.data.EntityReadAccess;
import org.ipro.fetch.instance.InstanceNameResolver;
import org.ipro.form.config.FormAutoConfiguration;
import org.ipro.form.link.FormLinkService;
import org.ipro.form.link.FormRouteOpener;
import org.ipro.register.RecorderTypeRegistry;
import org.ipro.register.RegisterKeyChangePermissions;
import org.ipro.register.RegisterKeyChanges;
import org.ipro.register.RegisterReaderFactory;
import org.ipro.register.RegisterRegistry;
import org.ipro.vaadin.register.RecorderRefPresentationResolver;
import org.ipro.vaadin.register.RegisterFormCustomization;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * D-R S4 — стандартный интерфейс регистра сведений: единственная авто-конфигурация
 * UI-артефакта.
 *
 * <p><b>Почему интерфейс приезжает отдельным артефактом, а не вместе с движком.</b> S0–S3
 * вынесли декларацию, чтение, запись по ключу и протокол набора владельца, не назвав ни одного
 * UI-типа; S4 приносит адаптер форм, диалог смены ключа и показ регистратора. Границы выбраны
 * так, что потребитель с любым интерфейсом получает движок без Vaadin, а потребитель с Vaadin
 * не дублирует стандартные формы — цена ошибки в другую сторону (UI в backend-артефакте)
 * измерена в D3.5: одно исключение превращает backend-модуль в UI-модуль для всех.</p>
 *
 * <p><b>Что здесь объявлено и что заменяемо.</b> Два бина: резолвер представлений регистратора
 * (каноническое чтение пачками под текущим C5/RLS-контекстом) и стандартный адаптер форм
 * ({@link RegisterFormCustomization}, то есть режимы «История/Срез последних», фазы отбора,
 * блокировка ключа, команда смены ключа и колонки регистратора). Адаптер заменяем
 * ({@code @ConditionalOnMissingBean}): потребитель, которому стандартный интерфейс не подходит
 * целиком, объявляет свой до того, как платформенный отступит. Замена — не единственная точка
 * расширения: слой форм применяет <i>все</i> {@code FormTypeCustomization}, поддерживающие тип,
 * поэтому частичная настройка добавляет свой адаптер рядом, а не вытесняет стандартный.</p>
 *
 * <p><b>Почему бин читателя не объявляется здесь.</b> Читатель среза приезжает фабрикой S1 из
 * {@code platform-register}: форма списка берёт его по дескриптору, а не собирает сама. Оставь
 * S4 свою сборку читателя — вернулся бы ровно тот дефект двух сборок, который S1 закрывал.</p>
 *
 * <p><b>Ваадин в артефакте — provided, и это делает условие обязательным.</b> Без
 * {@code @ConditionalOnClass} конфигурация на потребителе без UI-стека падала бы загрузкой
 * класса, а не отступала: provided-зависимость означает «Vaadin будет у того, кто нас
 * подключает», а не «у нас он есть всегда». Условие проверяет класс по имени, чтобы решение
 * принималось без загрузки отсутствующего типа.</p>
 */
@AutoConfiguration
@AutoConfigureAfter(FormAutoConfiguration.class)
@ConditionalOnClass(name = "com.vaadin.flow.component.Component")
public class RegisterVaadinConfiguration {

    @Bean
    public RecorderRefPresentationResolver recorderRefPresentationResolver(
            EntityReadAccess entityReadAccess, EntityDataAccessResolver entityDataAccessResolver,
            RecorderTypeRegistry recorderTypes, InstanceNameResolver instanceNames,
            FormLinkService formLinks) {
        return new RecorderRefPresentationResolver(entityReadAccess, entityDataAccessResolver,
            recorderTypes, instanceNames, formLinks);
    }

    @Bean
    @ConditionalOnMissingBean
    public RegisterFormCustomization registerFormCustomization(RegisterRegistry registers,
            EntityDataAccess access, RegisterReaderFactory readers, RegisterKeyChanges changes,
            RegisterKeyChangePermissions permissions, RecorderRefPresentationResolver captions,
            ObjectProvider<FormRouteOpener> routes, ObjectProvider<Clock> clocks) {
        return new RegisterFormCustomization(registers, access, readers, changes, permissions,
            captions, routes, clocks);
    }
}
