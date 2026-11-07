package org.ipro.form;

/** Why a value changed; fills react only to USER, while ordinary behavior also sees HANDLER. */
public enum ValueChangeSource {
    USER, HANDLER, INITIAL_LOAD, SAVED_STATE
}
