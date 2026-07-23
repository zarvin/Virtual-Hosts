package com.github.xfalcon.vhosts.data;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.*;

public class MigrationHelperTest {

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
    public void noMigrationIfAlreadyDone() throws Exception {
        // 模拟已迁移状态
        File migrationFlag = new File(baseDir, ".profiles_migrated");
        migrationFlag.createNewFile();

        MigrationHelper.migrateIfNeeded(repo, baseDir, "uri://old.txt", false, "net_hosts_content");

        // 不应创建新方案
        assertEquals(0, repo.findAll().size());
    }

    @Test
    public void migrateFromUri() throws Exception {
        String oldContent = "127.0.0.1 old.example.com\n";

        MigrationHelper.migrateIfNeeded(repo, baseDir, "uri://old.txt", false, oldContent);

        java.util.List<com.github.xfalcon.vhosts.model.HostProfile> all = repo.findAll();
        assertEquals(1, all.size());
        assertEquals("导入的 hosts", all.get(0).getTitle());
        assertTrue(all.get(0).isEnabled());
    }

    @Test
    public void migrateFromNetHosts() throws Exception {
        String netContent = "127.0.0.1 net.example.com\n";

        MigrationHelper.migrateIfNeeded(repo, baseDir, null, true, netContent);

        java.util.List<com.github.xfalcon.vhosts.model.HostProfile> all = repo.findAll();
        assertEquals(1, all.size());
        assertEquals("导入的 hosts", all.get(0).getTitle());
    }
}
