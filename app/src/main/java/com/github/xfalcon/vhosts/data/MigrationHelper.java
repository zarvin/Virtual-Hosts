package com.github.xfalcon.vhosts.data;

import com.github.xfalcon.vhosts.model.HostProfile;
import com.github.xfalcon.vhosts.util.LogUtils;

import java.io.File;
import java.io.IOException;

public class MigrationHelper {
    private static final String TAG = "MigrationHelper";
    private static final String FLAG_FILE = ".profiles_migrated";

    // 进程内串行化，配合 createNewFile 的原子占位，防止旋转/深链导致的并发重复迁移。
    private static final Object MIGRATION_LOCK = new Object();

    // 一次性迁移：把旧的单文件/网络 hosts 内容转成第一个已启用方案。
    // 供调用方在「主线程读旧文件之前」先判断，避免每次冷启动都读大文件。
    public static boolean needsMigration(File profilesDir) {
        return !new File(profilesDir, FLAG_FILE).exists();
    }

    public static void migrateIfNeeded(HostProfileRepository repo, File profilesDir,
                                       String hostUri, boolean isNet, String content) throws Exception {
        synchronized (MIGRATION_LOCK) {
            File migrationFlag = new File(profilesDir, FLAG_FILE);
            if (migrationFlag.exists()) {
                LogUtils.d(TAG, "Migration already done");
                return;
            }
            boolean hadLegacySource = isNet || hostUri != null;
            boolean readOk = content != null && !content.trim().isEmpty();
            if (hadLegacySource && !readOk) {
                // 本应有旧数据却没读到（SAF 权限/瞬时 IO 失败）：不占位、不落标志，
                // 下次启动重试，避免永久丢数据。
                LogUtils.w(TAG, "Legacy source present but content unreadable; will retry next launch");
                return;
            }
            // 原子占位：createNewFile 仅在文件不存在时创建成功（O_EXCL），
            // 抢占失败说明别的线程/进程已在迁移，直接跳过，杜绝重复方案。
            boolean claimed;
            try {
                claimed = migrationFlag.createNewFile();
            } catch (IOException e) {
                LogUtils.w(TAG, "Failed to claim migration flag; will retry next launch", e);
                return;
            }
            if (!claimed) {
                LogUtils.d(TAG, "Migration already claimed by another path");
                return;
            }
            try {
                if (readOk) {
                    String sourceType = isNet ? HostProfile.TYPE_URL : HostProfile.TYPE_FILE;
                    String sourceRef = isNet ? hostUri : null;  // URL 源记下地址便于将来刷新
                    repo.create("导入的 hosts", sourceType, sourceRef, content);
                    LogUtils.i(TAG, "Migrated legacy hosts to first profile");
                }
            } catch (Exception e) {
                // 迁移失败：撤销占位标志，下次启动重试，避免占位后永久跳过导致丢数据。
                if (!migrationFlag.delete()) {
                    LogUtils.w(TAG, "Failed to roll back migration flag after error");
                }
                throw e;
            }
        }
    }
}
