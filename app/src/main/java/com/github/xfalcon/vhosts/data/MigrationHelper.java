package com.github.xfalcon.vhosts.data;

import com.github.xfalcon.vhosts.util.LogUtils;

import java.io.File;

public class MigrationHelper {
    private static final String TAG = "MigrationHelper";
    private static final String FLAG_FILE = ".profiles_migrated";

    // 一次性迁移：把旧的单文件/网络 hosts 内容转成第一个已启用方案。
    // profilesDir 即仓储目录，迁移标志与方案数据同目录，确保只迁移一次。
    // 是否还需要迁移。供调用方在「主线程读旧文件之前」先判断，避免每次冷启动都读大文件。
    public static boolean needsMigration(File profilesDir) {
        return !new File(profilesDir, FLAG_FILE).exists();
    }

    public static void migrateIfNeeded(HostProfileRepository repo, File profilesDir,
                                       String hostUri, boolean isNet, String content) throws Exception {
        File migrationFlag = new File(profilesDir, FLAG_FILE);
        if (migrationFlag.exists()) {
            LogUtils.d(TAG, "Migration already done");
            return;
        }
        boolean hadLegacySource = isNet || hostUri != null;
        boolean readOk = content != null && !content.trim().isEmpty();
        if (hadLegacySource && !readOk) {
            // 本应有旧数据却没读到（SAF 权限/瞬时 IO 失败）：不落标志，下次启动重试，避免永久丢数据。
            LogUtils.w(TAG, "Legacy source present but content unreadable; will retry next launch");
            return;
        }
        if (readOk) {
            String sourceType = isNet ? "URL" : "FILE";
            String sourceRef = isNet ? hostUri : null;  // URL 源记下地址便于将来刷新
            repo.create("导入的 hosts", sourceType, sourceRef, content);
            LogUtils.i(TAG, "Migrated legacy hosts to first profile");
        }
        migrationFlag.createNewFile();
    }
}
