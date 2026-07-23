package com.github.xfalcon.vhosts.data;

import com.github.xfalcon.vhosts.model.HostProfile;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

public class HostProfileRepositoryTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HostProfileRepository repo;
    private File baseDir;

    @Before
    public void setUp() throws Exception {
        baseDir = tempFolder.newFolder("profiles");
        repo = new HostProfileRepository(baseDir);
    }

    @Test
    public void createAndFindAll() throws Exception {
        HostProfile p = repo.create("Test Hosts", "NEW", null, "127.0.0.1 example.com\n");
        assertNotNull(p.getId());
        assertTrue(p.isEnabled());
        assertEquals(0, p.getOrder());

        List<HostProfile> all = repo.findAll();
        assertEquals(1, all.size());
        assertEquals(p.getId(), all.get(0).getId());
    }

    @Test
    public void readContent() throws Exception {
        String content = "127.0.0.1 a.com\n127.0.0.1 b.com\n";
        HostProfile p = repo.create("Test", "NEW", null, content);
        String read = repo.readContent(p.getId());
        assertEquals(content.trim(), read);
    }

    @Test
    public void findEnabled() throws Exception {
        repo.create("P1", "NEW", null, "");
        repo.create("P2", "NEW", null, "");
        HostProfile p1 = repo.findAll().get(0);
        HostProfile p2 = repo.findAll().get(1);

        // 默认都启用
        List<HostProfile> enabled = repo.findEnabled();
        assertEquals(2, enabled.size());

        // 禁用第二个
        repo.updateMeta(p2.withEnabled(false));
        enabled = repo.findEnabled();
        assertEquals(1, enabled.size());
        assertEquals(p1.getId(), enabled.get(0).getId());
    }

    @Test
    public void updateMeta() throws Exception {
        HostProfile p = repo.create("Old", "NEW", null, "");
        HostProfile updated = p.withTitle("New").withOrder(5);
        repo.updateMeta(updated);

        HostProfile fetched = repo.findById(p.getId());
        assertEquals("New", fetched.getTitle());
        assertEquals(5, fetched.getOrder());
    }

    @Test
    public void updateContent() throws Exception {
        HostProfile p = repo.create("Test", "NEW", null, "old");
        repo.updateContent(p.getId(), "new content");
        String content = repo.readContent(p.getId());
        assertEquals("new content", content);
    }

    @Test
    public void delete() throws Exception {
        HostProfile p = repo.create("Test", "NEW", null, "");
        repo.delete(p.getId());
        List<HostProfile> all = repo.findAll();
        assertEquals(0, all.size());
    }

    @Test
    public void orderAndFindAll() throws Exception {
        HostProfile p1 = repo.create("P1", "NEW", null, "");
        HostProfile p2 = repo.create("P2", "NEW", null, "");
        // p1.order=0, p2.order=1
        List<HostProfile> all = repo.findAll();
        assertEquals(p1.getId(), all.get(0).getId());  // order 0 先
        assertEquals(p2.getId(), all.get(1).getId());  // order 1 后
    }
}
