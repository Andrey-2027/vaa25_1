package org.ipro.metadata.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** New entity/row contract: form settings belong to forms; legacy declarations fail startup.
 * Not inherited: captured platform base classes retain their compatibility declarations. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface FormSettingsOnly {}
