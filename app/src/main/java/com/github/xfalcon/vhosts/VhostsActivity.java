package com.github.xfalcon.vhosts;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.github.xfalcon.vhosts.data.HostProfileRepository;
import com.github.xfalcon.vhosts.data.HostsLoader;
import com.github.xfalcon.vhosts.data.MigrationHelper;
import com.github.xfalcon.vhosts.model.HostProfile;
import com.github.xfalcon.vhosts.ui.HostListAdapter;
import com.github.xfalcon.vhosts.util.HttpUtils;
import com.github.xfalcon.vhosts.util.LogUtils;
import com.github.xfalcon.vhosts.vservice.VhostsService;

import java.io.File;
import java.util.List;

public class VhostsActivity extends AppCompatActivity {
    private static final String TAG = VhostsActivity.class.getSimpleName();
    private static final int VPN_REQUEST_CODE = 0x0F;
    private static final int SELECT_FILE_CODE = 0x05;

    private RecyclerView recyclerView;
    private HostListAdapter adapter;
    private HostProfileRepository repo;
    private Button btnLaunch;
    private TextView emptyView;

    private android.content.BroadcastReceiver vpnStateReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (VhostsService.BROADCAST_VPN_STATE.equals(intent.getAction())) {
                updateLaunchButton();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        launch();  // 兼容旧的 "on"/"off" 深链接启动（外部快捷方式/自动化可能依赖）
        setContentView(R.layout.activity_vhosts);

        LogUtils.context = getApplicationContext();

        // 初始化仓储 & Adapter
        File profilesDir = new File(getFilesDir(), "profiles");
        repo = new HostProfileRepository(profilesDir);

        recyclerView = findViewById(R.id.recycler_profiles);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new HostListAdapter(this, profilesDir);
        adapter.setOnProfileClickListener(new HostListAdapter.OnProfileClickListener() {
            @Override
            public void onProfileClick(HostProfile profile) {
                Intent intent = new Intent(VhostsActivity.this, HostEditActivity.class);
                intent.putExtra(HostEditActivity.EXTRA_PROFILE_ID, profile.getId());
                startActivity(intent);
            }

            @Override
            public void onProfileLongClick(HostProfile profile) {
                showProfileActions(profile);
            }
        });
        recyclerView.setAdapter(adapter);

        emptyView = findViewById(R.id.empty_view);
        btnLaunch = findViewById(R.id.btn_launch);

        // 一次性迁移旧数据（内部已做「已迁移则不碰主线程 IO」的前置判断）
        migrateIfNeeded();

        // 更新列表显示
        refreshProfileList();

        // 启停按钮
        btnLaunch.setOnClickListener(v -> {
            if (VhostsService.isRunning()) {
                VhostsService.stopVService(VhostsActivity.this);
            } else {
                startVPN();
            }
        });

        // 广播监听 VPN 状态
        LocalBroadcastManager.getInstance(this).registerReceiver(vpnStateReceiver,
            new android.content.IntentFilter(VhostsService.BROADCAST_VPN_STATE));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.vhosts_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_add) {
            showAddMenu();
            return true;
        } else if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProfileList();
        updateLaunchButton();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(vpnStateReceiver);
    }

    private void refreshProfileList() {
        List<HostProfile> profiles = repo.findAll();
        adapter.setProfiles(profiles);
        emptyView.setVisibility(profiles.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
        recyclerView.setVisibility(profiles.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private void updateLaunchButton() {
        if (VhostsService.isRunning()) {
            btnLaunch.setText(R.string.stop);
        } else {
            btnLaunch.setText(R.string.launch);
        }
    }

    // 兼容旧入口：通过 Intent data（"on"/"off"）从外部快捷方式/自动化触发启停后直接结束。
    private void launch() {
        Uri uri = getIntent().getData();
        if (uri == null) return;
        String data = uri.toString();
        if ("on".equals(data)) {
            if (!VhostsService.isRunning()) VhostsService.startVService(this, 1);
            finish();
        } else if ("off".equals(data)) {
            VhostsService.stopVService(this);
            finish();
        }
    }

    private void startVPN() {
        Intent vpnIntent = VhostsService.prepare(this);
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        } else {
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
        }
    }

    private void showAddMenu() {
        String[] options = {
            getString(R.string.add_new),
            getString(R.string.add_from_file),
            getString(R.string.add_from_url)
        };

        new AlertDialog.Builder(this)
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0:
                        addNewProfile();
                        break;
                    case 1:
                        selectFileToImport();
                        break;
                    case 2:
                        addFromUrl();
                        break;
                }
            })
            .show();
    }

    // 长按方案：重命名 / 删除
    private void showProfileActions(final HostProfile profile) {
        String[] actions = { getString(R.string.rename), getString(R.string.delete) };
        new AlertDialog.Builder(this)
            .setTitle(profile.getTitle())
            .setItems(actions, (dialog, which) -> {
                if (which == 0) {
                    renameProfile(profile);
                } else {
                    confirmDelete(profile);
                }
            })
            .show();
    }

    private void renameProfile(final HostProfile profile) {
        final EditText input = new EditText(this);
        input.setText(profile.getTitle());
        new AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> {
                String title = input.getText().toString().trim();
                if (!title.isEmpty()) {
                    try {
                        repo.updateMeta(profile.withTitle(title));
                        refreshProfileList();
                    } catch (Exception e) {
                        LogUtils.e(TAG, "Rename error", e);
                    }
                }
            })
            .setNegativeButton(R.string.dialog_cancel, null)
            .show();
    }

    private void confirmDelete(final HostProfile profile) {
        new AlertDialog.Builder(this)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.delete, (dialog, which) -> {
                try {
                    repo.delete(profile.getId());
                    HostsLoader.reloadIfRunning(this);  // 运行中删除，即时从解析表移除
                    refreshProfileList();
                } catch (Exception e) {
                    LogUtils.e(TAG, "Delete error", e);
                }
            })
            .setNegativeButton(R.string.dialog_cancel, null)
            .show();
    }

    private void addNewProfile() {
        final EditText input = new EditText(this);
        input.setHint(R.string.enter_title);

        new AlertDialog.Builder(this)
            .setTitle(R.string.add_new)
            .setView(input)
            .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> {
                String title = input.getText().toString().trim();
                if (!title.isEmpty()) {
                    try {
                        HostProfile p = repo.create(title, "NEW", null, "");
                        refreshProfileList();
                        // 进编辑页
                        Intent intent = new Intent(VhostsActivity.this, HostEditActivity.class);
                        intent.putExtra(HostEditActivity.EXTRA_PROFILE_ID, p.getId());
                        startActivity(intent);
                    } catch (Exception e) {
                        LogUtils.e(TAG, "Error creating profile", e);
                        Toast.makeText(VhostsActivity.this, R.string.err_create, Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton(R.string.dialog_cancel, null)
            .show();
    }

    private void selectFileToImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, SELECT_FILE_CODE);
    }

    private void addFromUrl() {
        final EditText input = new EditText(this);
        input.setHint(R.string.add_from_url);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);

        new AlertDialog.Builder(this)
            .setTitle(R.string.add_from_url)
            .setView(input)
            .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> {
                String url = input.getText().toString().trim();
                if (!isValidUrl(url)) {
                    Toast.makeText(VhostsActivity.this, R.string.invalid_url, Toast.LENGTH_SHORT).show();
                    return;
                }
                downloadFromUrl(url);
            })
            .setNegativeButton(R.string.dialog_cancel, null)
            .show();
    }

    private void downloadFromUrl(final String url) {
        // 后台下载
        new Thread() {
            public void run() {
                try {
                    final String content = HttpUtils.get(url);
                    String name = Uri.parse(url).getLastPathSegment();
                    if (name == null || name.isEmpty()) name = Uri.parse(url).getHost();
                    if (name == null || name.isEmpty()) name = "hosts";
                    repo.create(name, "URL", url, content);
                    runOnUiThread(() -> {
                        int records = countRecords(content);
                        Toast.makeText(VhostsActivity.this,
                            getString(R.string.records_count, records),
                            Toast.LENGTH_SHORT).show();
                        refreshProfileList();
                    });
                } catch (Exception e) {
                    LogUtils.e(TAG, "Download error", e);
                    runOnUiThread(() -> Toast.makeText(VhostsActivity.this, R.string.down_error, Toast.LENGTH_SHORT).show());
                }
            }
        }.start();
    }

    private boolean isValidUrl(String str) {
        String regex = "http(s)?://([\\w-]+\\.)+[\\w-]+(/[\\w- ./?%&=]*)?";
        return str.matches(regex);
    }

    private int countRecords(String content) {
        int count = 0;
        for (String line : content.split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                count++;
            }
        }
        return count;
    }

    // 迁移：已迁移则不碰主线程 IO；未迁移时整体放后台线程读旧文件并转成首个方案。
    private void migrateIfNeeded() {
        final File profilesDir = new File(getFilesDir(), "profiles");
        if (!MigrationHelper.needsMigration(profilesDir)) {
            return;
        }
        final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        final String hostUri = settings.getString("HOST_URI", null);
        final boolean isNet = settings.getBoolean("IS_NET", false);
        new Thread() {
            public void run() {
                try {
                    String content = null;
                    if (isNet) {
                        try { content = readFile(openFileInput("net_hosts")); } catch (Exception ignore) {}
                    } else if (hostUri != null) {
                        try { content = readFile(getContentResolver().openInputStream(Uri.parse(hostUri))); } catch (Exception ignore) {}
                    }
                    MigrationHelper.migrateIfNeeded(repo, profilesDir, hostUri, isNet, content);
                    runOnUiThread(() -> refreshProfileList());
                } catch (Exception e) {
                    LogUtils.e(TAG, "Migration error", e);
                }
            }
        }.start();
    }

    private String readFile(java.io.InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is));
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append("\n");
        }
        br.close();
        is.close();
        return sb.toString();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            // VPN 授权通过
            startService(new Intent(this, VhostsService.class).setAction(VhostsService.ACTION_CONNECT));
        } else if (requestCode == SELECT_FILE_CODE && resultCode == RESULT_OK && data != null) {
            // 文件导入（放后台线程读取，避免大文件卡 UI）
            final Uri fileUri = data.getData();
            new Thread() {
                public void run() {
                    try {
                        final String content = readFile(getContentResolver().openInputStream(fileUri));
                        final String title = getFileName(fileUri);
                        repo.create(title, "FILE", null, content);
                        runOnUiThread(() -> {
                            int records = countRecords(content);
                            Toast.makeText(VhostsActivity.this,
                                getString(R.string.records_count, records),
                                Toast.LENGTH_SHORT).show();
                            refreshProfileList();
                        });
                    } catch (Exception e) {
                        LogUtils.e(TAG, "Import error", e);
                        runOnUiThread(() -> Toast.makeText(VhostsActivity.this, R.string.err_import, Toast.LENGTH_SHORT).show());
                    }
                }
            }.start();
        }
    }

    private String getFileName(Uri uri) {
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    String name = cursor.getString(nameIndex);
                    if (name != null && !name.isEmpty()) {
                        return name.replaceAll("\\.[^.]*$", "");  // 去掉扩展名
                    }
                }
            }
        } catch (Exception e) {
            LogUtils.e(TAG, "getFileName error", e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return "hosts";  // 取不到文件名时的兜底标题
    }
}
