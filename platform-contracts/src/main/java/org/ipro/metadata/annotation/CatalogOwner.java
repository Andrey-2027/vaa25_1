package org.ipro.metadata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Явная возможность «подчинённый справочник»: тип объявляет одного владельца, значение
 * которого обязательно и не меняется после сохранения.
 *
 * <p>Capability задаётся объявлением, а не именем поля и не обычной ссылкой: поле с именем
 * {@code owner}/{@code parent} без этой аннотации остаётся обычной ссылкой. Аннотация
 * ставится на catalog root; документ, строка секции и тип без CATALOG-семантики её не
 * получают. {@code @Inherited} намеренно отсутствует — capability не наследуется.</p>
 *
 * <p>Тип цели выводится из объявленной ассоциации, а не из дополнительного атрибута:
 * {@code property} называет единственное поле-владельца.</p>
 *
 * <pre>
 * {@code
 * @Entity
 * @EntityMetadata(listFormTitle = "Договоры")
 * @CatalogOwner(property = "counterparty")
 * public class Contract extends StandardCatalogEntity {
 *     @ManyToOne(optional = false)
 *     @JoinColumn(name = "counterparty_id", nullable = false)
 *     @NotNull
 *     private Counterparty counterparty;
 * }
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface CatalogOwner {

    /** Имя Java-поля одиночной ManyToOne-ассоциации, объявляющей владельца. */
    String property();
}
