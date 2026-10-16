package org.ipro.register;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PreUpdateEvent;
import org.hibernate.event.spi.PreUpdateEventListener;
import org.ipro.identity.IdentifiableEntity;
import org.springframework.beans.factory.InitializingBean;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Compares Hibernate snapshots even for raw managed writes outside canonical lifecycle. */
public final class RegisterKeyWriteGuardInstaller implements InitializingBean {
    private final EntityManagerFactory entityManagerFactory;

    public RegisterKeyWriteGuardInstaller(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    @Override public void afterPropertiesSet() {
        SessionFactoryImplementor factory = entityManagerFactory.unwrap(SessionFactoryImplementor.class);
        for (var entity : entityManagerFactory.getMetamodel().getEntities()) {
            Class<?> type = entity.getJavaType();
            RegisterMetadata declaration = type.getAnnotation(RegisterMetadata.class);
            if (declaration == null) continue;
            var persister = factory.getMappingMetamodel().getEntityDescriptor(type);
            var names = java.util.Arrays.asList(persister.getPropertyNames());
            for (String dimension : declaration.dimensions()) {
                requireMapping(names, persister.getPropertyUpdateability(), dimension, declaration.keyChangeAllowed(), type);
            }
            if (!declaration.period().isBlank()) {
                requireMapping(names, persister.getPropertyUpdateability(), declaration.period(), declaration.keyChangeAllowed(), type);
            }
            if (RecordedRegisterEntry.class.isAssignableFrom(type)) {
                for (String protectedField : java.util.List.of("recorder", "lineNumber", "recordingOrder")) {
                    requireMapping(names, persister.getPropertyUpdateability(), protectedField, false, type);
                }
            }
        }
        factory.getServiceRegistry().requireService(EventListenerRegistry.class)
            .prependListeners(EventType.PRE_UPDATE, new Guard());
    }

    private static void requireMapping(java.util.List<String> names, boolean[] updateability,
                                       String field, boolean expected, Class<?> type) {
        int index = names.indexOf(field);
        if (index < 0 || updateability[index] != expected) {
            throw new IllegalStateException("Register Hibernate mapping disagrees with key capability: "
                + type.getName() + "." + field + ", expected updatable=" + expected);
        }
    }

    private static final class Guard implements PreUpdateEventListener {
        @Override public boolean onPreUpdate(PreUpdateEvent event) {
            Class<?> type = event.getPersister().getMappedClass();
            RegisterMetadata declaration = type.getAnnotation(RegisterMetadata.class);
            if (declaration == null) return false;
            if (event.getOldState() == null) throw denied();
            Map<String, Object> oldKey = key(event, declaration, event.getOldState());
            Map<String, Object> newKey = key(event, declaration, event.getState());
            String[] names = event.getPersister().getPropertyNames();
            var types = event.getPersister().getPropertyTypes();
            for (int i = 0; i < names.length; i++) {
                if (RecordedRegisterEntry.class.isAssignableFrom(type)
                        && ("recorder".equals(names[i]) || "lineNumber".equals(names[i])
                            || "recordingOrder".equals(names[i]))
                        && !types[i].isEqual(event.getOldState()[i], event.getState()[i],
                            event.getSession().getFactory())) throw denied();
            }
            if (!oldKey.equals(newKey)
                    && (!declaration.keyChangeAllowed()
                        || !RegisterKeyChangeScope.permits(event, oldKey, newKey))) throw denied();
            // Check the whole row even if a callback has restored the key but changed a resource.
            if (RegisterKeyChangeScope.permits(event.getEntity())
                    && !RegisterKeyChangeScope.permits(event, oldKey, newKey)) throw denied();
            return false;
        }

        /**
         * Ключ строки для сравнения: правило владеет и порядком «нормализация → сравнение».
         *
         * <p>Замерено, а не предположено: сегодня {@link RegisterPeriodListener} (JPA-callback)
         * успевает выровнять период раньше этого забора, поэтому правка времени внутри того же
         * дня не выглядит сменой ключа и <b>без</b> нормализации здесь (проверено снятием строки:
         * {@code NomenclaturePricePersistenceTest.managedPeriodEditInsideTheSameDailyBucketKeepsTheKey}
         * остаётся зелёным). Нормализация оставлена осознанно: иначе порядок между JPA-callback'ом
         * и слушателем события Hibernate становится скрытой зависимостью правила — а это деталь
         * реализации Hibernate, а не обещание платформы, и её смена молча превратила бы законную
         * правку в запрет.
         */
        private static Map<String, Object> key(PreUpdateEvent event, RegisterMetadata declaration, Object[] state) {
            var names = java.util.Arrays.asList(event.getPersister().getPropertyNames());
            var key = new LinkedHashMap<String, Object>();
            for (String field : declaration.dimensions()) key.put(field, scalar(state[names.indexOf(field)]));
            if (!declaration.period().isBlank()) {
                Object raw = state[names.indexOf(declaration.period())];
                key.put(declaration.period(), raw instanceof LocalDateTime period
                    ? declaration.periodicity().normalize(period) : raw);
            }
            return key;
        }
    }

    static Object scalar(Object value) {
        return value instanceof IdentifiableEntity<?> entity ? entity.getId() : value;
    }

    private static IllegalStateException denied() {
        return new IllegalStateException("REGISTER_KEY_CHANGE_REQUIRED: ключ записи можно менять только действием «Изменить ключ записи».");
    }
}
