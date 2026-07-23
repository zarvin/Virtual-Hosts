package com.github.xfalcon.vhosts;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.github.xfalcon.vhosts.data.HostProfileRepository;
import com.github.xfalcon.vhosts.data.HostsLoader;
import com.github.xfalcon.vhosts.model.HostProfile;
import com.github.xfalcon.vhosts.util.LogUtils;

import java.io.File;

public class HostEditActivity extends AppCompatActivity {
    private static final String TAG = "HostEditActivity";
    public static final String EXTRA_PROFILE_ID = "profile_id";

    private EditText editTitle;
    private EditText editContent;
    private Button btnSave;
    private android.widget.ImageButton btnBack;

    private String profileId;
    private HostProfileRepository repo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_host_edit);

        editTitle = findViewById(R.id.edit_title);
        editContent = findViewById(R.id.edit_content);
        btnSave = findViewById(R.id.btn_save);
        btnBack = findViewById(R.id.btn_back);

        File profilesDir = new File(getFilesDir(), "profiles");
        repo = new HostProfileRepository(profilesDir);

        profileId = getIntent().getStringExtra(EXTRA_PROFILE_ID);
        if (profileId == null) {
            Toast.makeText(this, "Error: No profile ID", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        loadProfile();

        btnBack.setOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> saveProfile());
    }

    private void loadProfile() {
        try {
            HostProfile p = repo.findById(profileId);
            if (p == null) {
                Toast.makeText(this, "Error: Profile not found", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            editTitle.setText(p.getTitle());
            String content = repo.readContent(profileId);
            editContent.setText(content);
        } catch (Exception e) {
            LogUtils.e(TAG, "Error loading profile", e);
            Toast.makeText(this, "Error loading profile", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveProfile() {
        try {
            String title = editTitle.getText().toString().trim();
            String content = editContent.getText().toString().trim();

            if (title.isEmpty()) {
                Toast.makeText(this, "Title cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }

            HostProfile p = repo.findById(profileId);
            if (p == null) {
                Toast.makeText(this, "Error: Profile not found", Toast.LENGTH_SHORT).show();
                return;
            }

            // 更新标题和内容
            repo.updateMeta(p.withTitle(title));
            repo.updateContent(profileId, content);

            // 若 VPN 运行中则重载
            HostsLoader.reloadIfRunning(this);

            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            finish();
        } catch (Exception e) {
            LogUtils.e(TAG, "Error saving profile", e);
            Toast.makeText(this, "Error saving", Toast.LENGTH_SHORT).show();
        }
    }
}
