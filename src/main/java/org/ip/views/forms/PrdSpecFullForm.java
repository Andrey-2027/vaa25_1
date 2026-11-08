package org.ip.views.forms;

import org.ip.model.PrdSpec;
import org.ipro.form.builder.FormFor;

/** Explicit full variant retains the same two sections as the default form. */
@FormFor(value = PrdSpec.class, variant = "full")
public class PrdSpecFullForm extends PrdSpecItemForm { }
