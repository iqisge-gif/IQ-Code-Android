package com.termux.app.iqcode.api;

/** A safe, display-ready model entry returned by an API catalog. */
public final class ModelDescriptor {
    public final String id;
    public final String displayName;

    public ModelDescriptor(String id, String displayName) {
        this.id = id;
        this.displayName = displayName == null || displayName.trim().isEmpty() ? id : displayName.trim();
    }
}
