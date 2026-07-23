package com.github.xfalcon.vhosts.model;

public final class HostProfile {
    private final String id;
    private final String title;
    private final boolean enabled;
    private final int order;
    private final String sourceType;  // NEW | FILE | URL
    private final String sourceRef;   // null for NEW/FILE; URL string for URL

    private HostProfile(String id, String title, boolean enabled, int order, String sourceType, String sourceRef) {
        this.id = id;
        this.title = title;
        this.enabled = enabled;
        this.order = order;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
    }

    public static HostProfile create(String id, String title, boolean enabled, int order, String sourceType, String sourceRef) {
        return new HostProfile(id, title, enabled, order, sourceType, sourceRef);
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public boolean isEnabled() { return enabled; }
    public int getOrder() { return order; }
    public String getSourceType() { return sourceType; }
    public String getSourceRef() { return sourceRef; }

    public HostProfile withTitle(String newTitle) {
        return new HostProfile(id, newTitle, enabled, order, sourceType, sourceRef);
    }

    public HostProfile withEnabled(boolean newEnabled) {
        return new HostProfile(id, title, newEnabled, order, sourceType, sourceRef);
    }

    public HostProfile withOrder(int newOrder) {
        return new HostProfile(id, title, enabled, newOrder, sourceType, sourceRef);
    }

    @Override
    public String toString() {
        return "HostProfile{" + "id=" + id + ", title=" + title + ", enabled=" + enabled +
               ", order=" + order + ", sourceType=" + sourceType + "}";
    }
}
