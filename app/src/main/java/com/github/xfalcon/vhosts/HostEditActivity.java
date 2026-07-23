package com.github.xfalcon.vhosts;

import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.github.xfalcon.vhosts.data.HostProfileRepository;
import com.github.xfalcon.vhosts.data.HostsLoader;
import com.github.xfalcon.vhosts.model.HostProfile;
import com.github.xfalcon.vhosts.util.LogUtils;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HostEditActivity extends AppCompatActivity {
    private static final String TAG = "HostEditActivity";
    public static final String EXTRA_PROFILE_ID = "profile_id";

    // 行首第一个 token 视为 IP（IPv4/IPv6 字符集）
    private static final Pattern IP_PATTERN = Pattern.compile("^\\s*([0-9a-fA-F.:]+)");
    private static final int COLOR_IP = 0xFF1976D2;      // 蓝：IP
    private static final int COLOR_COMMENT = 0xFF999999; // 灰：# 注释

    private EditText editTitle;
    private EditText editContent;
    private TextView lineNumbers;

    private String profileId;
    private HostProfileRepository repo;

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

        editContent.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                updateLineNumbers(s);
                highlight(s);
            }
        });

        loadProfile();
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

    private void updateLineNumbers(CharSequence s) {
        int lines = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') lines++;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            sb.append(i).append('\n');
        }
        lineNumbers.setText(sb);
    }

    // hosts 简单高亮：# 注释整行灰；否则行首第一个 token（IP）蓝。
    // setSpan 只改 span 不改字符，不会再触发 TextWatcher，无递归。
    private void highlight(Editable s) {
        ForegroundColorSpan[] old = s.getSpans(0, s.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan sp : old) s.removeSpan(sp);

        String text = s.toString();
        int start = 0;
        while (start <= text.length()) {
            int nl = text.indexOf('\n', start);
            int end = (nl == -1) ? text.length() : nl;
            String line = text.substring(start, end);
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                s.setSpan(new ForegroundColorSpan(COLOR_COMMENT), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (!trimmed.isEmpty()) {
                Matcher m = IP_PATTERN.matcher(line);
                if (m.find()) {
                    s.setSpan(new ForegroundColorSpan(COLOR_IP), start + m.start(1), start + m.end(1), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (nl == -1) break;
            start = nl + 1;
        }
    }
}
