package com.github.xfalcon.vhosts.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class HostProfileTest {
    @Test
    public void createAndGetFields() {
        HostProfile p = HostProfile.create("abc-id", "My Hosts", true, 0, "NEW", null);
        assertEquals("abc-id", p.getId());
        assertEquals("My Hosts", p.getTitle());
        assertTrue(p.isEnabled());
        assertEquals(0, p.getOrder());
        assertEquals("NEW", p.getSourceType());
        assertNull(p.getSourceRef());
    }

    @Test
    public void immutableWithTitle() {
        HostProfile p1 = HostProfile.create("id1", "Old", true, 0, "NEW", null);
        HostProfile p2 = p1.withTitle("New");
        assertEquals("Old", p1.getTitle());
        assertEquals("New", p2.getTitle());
        assertEquals("id1", p2.getId());
    }

    @Test
    public void immutableWithEnabled() {
        HostProfile p1 = HostProfile.create("id1", "T", true, 0, "NEW", null);
        HostProfile p2 = p1.withEnabled(false);
        assertTrue(p1.isEnabled());
        assertFalse(p2.isEnabled());
    }

    @Test
    public void immutableWithOrder() {
        HostProfile p1 = HostProfile.create("id1", "T", true, 0, "NEW", null);
        HostProfile p2 = p1.withOrder(5);
        assertEquals(0, p1.getOrder());
        assertEquals(5, p2.getOrder());
    }

    @Test
    public void sourceTypeAndRef() {
        HostProfile p = HostProfile.create("id1", "T", true, 0, "URL", "https://example.com/hosts");
        assertEquals("URL", p.getSourceType());
        assertEquals("https://example.com/hosts", p.getSourceRef());
    }
}
