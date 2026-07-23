package com.github.xfalcon.vhosts;

import android.os.Bundle;
import android.text.SpannableString;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.github.xfalcon.vhosts.util.HostsHighlighter;
import com.github.xfalcon.vhosts.vservice.DnsChange;

// 只读展示当前生效（多方案合并、靠前优先去重后）的所有 IP→域名映射。
public class ActiveHostsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_active_hosts);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.active_hosts);
        }

        TextView lineNumbers = findViewById(R.id.line_numbers);
        TextView content = findViewById(R.id.active_content);
        TextView emptyView = findViewById(R.id.empty_view);

        String text = DnsChange.getActiveHostsText();
        if (text == null || text.trim().isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
        } else {
            SpannableString ss = new SpannableString(text);
            HostsHighlighter.apply(ss);
            content.setText(ss);
            lineNumbers.setText(HostsHighlighter.lineNumbers(text));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.active_hosts_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_help) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.active_hosts_help_title)
                .setMessage(R.string.active_hosts_help_msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
