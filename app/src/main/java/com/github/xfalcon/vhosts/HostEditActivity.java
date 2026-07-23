package com.github.xfalcon.vhosts;

import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.github.xfalcon.vhosts.data.HostProfileRepository;
import com.github.xfalcon.vhosts.data.HostsLoader;
import com.github.xfalcon.vhosts.model.HostProfile;
import com.github.xfalcon.vhosts.util.HostsHighlighter;
import com.github.xfalcon.vhosts.util.LogUtils;
import org.xbill.DNS.Address;

import java.io.File;

public class HostEditActivity extends AppCompatActivity {
    private static final String TAG = "HostEditActivity";
    public static final String EXTRA_PROFILE_ID = "profile_id";

    // 高亮 debounce 延迟；超过该长度（约数千行）跳过高亮，只保留行号，避免超大订阅卡顿。
    private static final long HIGHLIGHT_DEBOUNCE_MS = 250;
    private static final int HIGHLIGHT_MAX_LEN = 120000;

    private EditText editTitle;
    private EditText editContent;
    private TextView lineNumbers;

    private String profileId;
    private HostProfileRepository repo;

    private final Handler highlightHandler = new Handler();
    private Runnable highlightRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_host_edit);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);  // 左上角关闭（返回）
            getSupportActionBar().setTitle(R.string.edit);
        }

        editTitle = findViewById(R.id.edit_title);
        editContent = findViewById(R.id.edit_content);
        lineNumbers = findViewById(R.id.line_numbers);

        File profilesDir = new File(getFilesDir(), "profiles");
        repo = new HostProfileRepository(profilesDir);

        profileId = getIntent().getStringExtra(EXTRA_PROFILE_ID);
        if (profileId == null) {
            Toast.makeText(this, R.string.err_no_profile_id, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        highlightRunnable = new Runnable() {
            public void run() {
                Editable e = editContent.getText();
                if (e != null) HostsHighlighter.apply(e);
            }
        };

        editContent.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                lineNumbers.setText(HostsHighlighter.lineNumbers(s));  // 行号即时更新
                // 高亮去抖：连续输入时不每次全量重算；超大文本跳过高亮避免卡顿。
                highlightHandler.removeCallbacks(highlightRunnable);
                if (s.length() <= HIGHLIGHT_MAX_LEN) {
                    highlightHandler.postDelayed(highlightRunnable, HIGHLIGHT_DEBOUNCE_MS);
                }
            }
        });

        loadProfile();
    }

    @Override
    protected void onDestroy() {
        highlightHandler.removeCallbacks(highlightRunnable);
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.edit_menu, menu);  // 右上角保存
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();  // 关闭（不保存）
            return true;
        } else if (id == R.id.action_save) {
            saveProfile();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadProfile() {
        try {
            HostProfile p = repo.findById(profileId);
            if (p == null) {
                Toast.makeText(this, R.string.err_profile_not_found, Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            editTitle.setText(p.getTitle());
            editContent.setText(repo.readContent(profileId));  // 触发 TextWatcher → 行号 + 高亮
        } catch (Exception e) {
            LogUtils.e(TAG, "Error loading profile", e);
            Toast.makeText(this, R.string.err_load_profile, Toast.LENGTH_SHORT).show();
        }
    }

    private void saveProfile() {
        try {
            String title = editTitle.getText().toString().trim();
            String content = editContent.getText().toString().trim();
            if (title.isEmpty()) {
                Toast.makeText(this, R.string.title_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            // 保存前校验 hosts 格式，有错则提示并中止
            String error = validateHosts(content);
            if (error != null) {
                new AlertDialog.Builder(this)
                    .setTitle(R.string.format_error)
                    .setMessage(error)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
                return;
            }
            HostProfile p = repo.findById(profileId);
            if (p == null) {
                Toast.makeText(this, R.string.err_profile_not_found, Toast.LENGTH_SHORT).show();
                return;
            }
            repo.updateMeta(p.withTitle(title));
            repo.updateContent(profileId, content);
            HostsLoader.reloadIfRunning(this);
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            LogUtils.e(TAG, "Error saving profile", e);
            Toast.makeText(this, R.string.err_save, Toast.LENGTH_SHORT).show();
        }
    }

    // 逐行校验 hosts 格式：跳过空行与 # 注释；其余行必须是「IP 域名」，
    // IP 用与 DnsChange 相同的 Address.getByAddress 验证。返回首个错误提示，全部合法则 null。
    private String validateHosts(String content) {
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\s+");
            if (parts.length < 2 || parts[1].isEmpty()) {
                return getString(R.string.err_line_format, i + 1);
            }
            try {
                Address.getByAddress(parts[0]);
            } catch (Exception e) {
                return getString(R.string.err_line_ip, i + 1, parts[0]);
            }
        }
        return null;
    }
}
