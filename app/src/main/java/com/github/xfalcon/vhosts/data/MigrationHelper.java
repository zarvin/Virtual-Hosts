package com.github.xfalcon.vhosts.data;

import com.github.xfalcon.vhosts.util.LogUtils;

import java.io.File;

public class MigrationHelper {
    private static final String TAG = "MigrationHelper";
    private static final String FLAG_FILE = ".profiles_migrated";

    // 一次性迁移：把旧的单文件/网络 hosts 内容转成第一个已启用方案。
    // profilesDir 即仓储目录，迁移标志与方案数据同目录，确保只迁移一次。
    public static void migrateIfNeeded(HostProfileRepository repo, File profilesDir,
                                       String hostUri, boolean isNet, String content) throws Exception {
        File migrationFlag = new File(profilesDir, FLAG_FILE);
        if (migrationFlag.exists()) {
            LogUtils.d(TAG, "Migration already done");
            return;
        }
        if (content != null && !content.trim().isEmpty()) {
            String sourceType = isNet ? "URL" : "FILE";
            String sourceRef = isNet ? hostUri : null;  // URL 源记下地址便于将来刷新
            repo.create("导入的 hosts", sourceType, sourceRef, content);
            LogUtils.i(TAG, "Migrated legacy hosts to first profile");
        }
        migrationFlag.createNewFile();
    }
}
