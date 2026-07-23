package com.github.xfalcon.vhosts.util;

import android.text.Spannable;
import android.text.style.ForegroundColorSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// hosts 文本的行号与语法高亮，供编辑页与「生效 hosts」查看页共用。
public class HostsHighlighter {
    private static final Pattern IP_PATTERN = Pattern.compile("^\\s*([0-9a-fA-F.:]+)");
    private static final int COLOR_IP = 0xFF1976D2;      // 蓝：IP
    private static final int COLOR_COMMENT = 0xFF999999; // 灰：# 注释

    // 行首第一个 token（IP）蓝、# 注释整行灰。只改 span 不改字符，不触发 TextWatcher。
    public static void apply(Spannable s) {
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

    // 依据文本行数生成 "1\n2\n…\nN" 行号文本。
    public static String lineNumbers(CharSequence s) {
        int lines = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') lines++;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            sb.append(i).append('\n');
        }
        return sb.toString();
    }
}
