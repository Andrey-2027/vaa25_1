package org.ipro.navigation;

/** A profile override. DEFAULT inherits the developer's visibility for this target. */
public enum NavigationVisibility {
    DEFAULT, SHOW, HIDE;

    boolean resolve(boolean developerDefault) {
        return switch (this) {
            case DEFAULT -> developerDefault;
            case SHOW -> true;
            case HIDE -> false;
        };
    }
}
