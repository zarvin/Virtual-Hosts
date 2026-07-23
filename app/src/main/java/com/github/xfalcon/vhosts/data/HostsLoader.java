package com.github.xfalcon.vhosts.data;

import android.content.Context;
import com.github.xfalcon.vhosts.model.HostProfile;
import com.github.xfalcon.vhosts.util.LogUtils;
import com.github.xfalcon.vhosts.vservice.DnsChange;
import com.github.xfalcon.vhosts.vservice.VhostsService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class HostsLoader {
    private static final String TAG = "HostsLoader";

    // 单线程串行化重载：快速连续切开关/保存时，多个并发重载可能"后发先至"留下旧表；
    // 用单线程队列保证按提交顺序依次执行。
    private static final java.util.concurrent.ExecutorService RELOAD_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    // 从仓储读所有已启用方案，按顺序（靠前优先）合并加载到 DnsChange。
    // 会读文件，调用方需在后台线程执行。返回已加载的方案数量。
    public static int reload(HostProfileRepository repo) throws Exception {
        List<HostProfile> enabled = repo.findEnabled();
        List<String> contents = new ArrayList<>();
        for (HostProfile p : enabled) {
            contents.add(repo.readContent(p.getId()));
        }
        DnsChange.loadProfiles(contents);
        return enabled.size();
    }

    // 供服务启动时的首次加载复用同一串行队列，与运行时热重载全局串行，避免两加载入口竞态。
    public static void runOnLoader(Runnable task) {
        RELOAD_EXECUTOR.execute(task);
    }

    // 运行时重载：仅当 VPN 正在运行时，后台重建解析表，隧道不断。
    public static void reloadIfRunning(final Context context) {
        if (!VhostsService.isRunning()) {
            return;
        }
        final Context appContext = context.getApplicationContext();
        RELOAD_EXECUTOR.execute(new Runnable() {
            public void run() {
                try {
                    HostProfileRepository repo = new HostProfileRepository(new File(appContext.getFilesDir(), "profiles"));
                    int n = reload(repo);
                    LogUtils.i(TAG, "Reloaded " + n + " profiles");
                } catch (Exception e) {
                    LogUtils.e(TAG, "Error reloading profiles", e);
                }
            }
        });
    }
}
