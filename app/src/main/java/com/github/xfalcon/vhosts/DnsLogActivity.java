package com.github.xfalcon.vhosts;

import android.os.Bundle;
import android.text.SpannableString;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.github.xfalcon.vhosts.util.HostsHighlighter;
import com.github.xfalcon.vhosts.vservice.DnsChange;

import java.util.List;

// 显示最近的 DNS 命中记录（本地作答的 IP  域名），可清空。
public class DnsLogActivity extends AppCompatActivity {

    private TextView content;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dns_log);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.dns_log);
        }

        content = findViewById(R.id.dns_log_content);
        emptyView = findViewById(R.id.empty_view);
        render();
    }

    private void render() {
        List<String> log = DnsChange.getHitLog();
        if (log.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            content.setText("");
        } else {
            emptyView.setVisibility(View.GONE);
            StringBuilder sb = new StringBuilder();
            for (String s : log) sb.append(s).append("\n");
            SpannableString ss = new SpannableString(sb.toString());
            HostsHighlighter.apply(ss);  // 行首 IP 高亮
            content.setText(ss);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.dns_log_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        } else if (id == R.id.action_clear) {
            DnsChange.clearHitLog();
            render();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
