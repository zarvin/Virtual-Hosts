package com.github.xfalcon.vhosts.model;

public final class HostProfile {
    public static final String TYPE_NEW = "NEW";
    public static final String TYPE_FILE = "FILE";
    public static final String TYPE_URL = "URL";

    private final String id;
    private final String title;
    private final boolean enabled;
    private final int order;
    private final String sourceType;  // NEW | FILE | URL
    private final String sourceRef;   // null for NEW/FILE; URL string for URL

    private HostProfile(String id, String title, boolean enabled, int order, String sourceType, String sourceRef) {
        if (id == null) throw new IllegalArgumentException("HostProfile.id must not be null");
        if (sourceType == null) throw new IllegalArgumentException("HostProfile.sourceType must not be null");
        this.id = id;
        this.title = title == null ? "" : title;
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
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HostProfile)) return false;
        HostProfile that = (HostProfile) o;
        return enabled == that.enabled && order == that.order
                && id.equals(that.id) && title.equals(that.title)
                && sourceType.equals(that.sourceType)
                && (sourceRef == null ? that.sourceRef == null : sourceRef.equals(that.sourceRef));
    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + title.hashCode();
        result = 31 * result + (enabled ? 1 : 0);
        result = 31 * result + order;
        result = 31 * result + sourceType.hashCode();
        result = 31 * result + (sourceRef == null ? 0 : sourceRef.hashCode());
        return result;
    }

    @Override
    public String toString() {
        return "HostProfile{" + "id=" + id + ", title=" + title + ", enabled=" + enabled +
               ", order=" + order + ", sourceType=" + sourceType + "}";
    }
}
