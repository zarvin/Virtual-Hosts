package com.github.xfalcon.vhosts.data;

import com.github.xfalcon.vhosts.model.HostProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// 所有方案的导入/导出（JSON，含内容）。org.json 是 Android 运行时自带，仅在 App 内使用。
public class ImportExportHelper {
    // 输入上限，防御恶意/超大 JSON 撑爆内存或耗尽存储（CRITICAL）。
    private static final int MAX_JSON_CHARS = 64 * 1024 * 1024;   // 整个 JSON 文本 ≤ 64MB
    private static final int MAX_PROFILES = 1000;                 // 方案条数上限
    private static final int MAX_CONTENT_CHARS = 8 * 1024 * 1024; // 单条内容 ≤ 8MB
    private static final int MAX_TITLE_CHARS = 256;

    public static String export(HostProfileRepository repo) throws Exception {
        JSONArray arr = new JSONArray();
        for (HostProfile p : repo.findAll()) {
            JSONObject o = new JSONObject();
            o.put("title", p.getTitle());
            o.put("enabled", p.isEnabled());
            o.put("sourceType", p.getSourceType());
            o.put("sourceRef", p.getSourceRef() == null ? JSONObject.NULL : p.getSourceRef());
            try {
                o.put("content", repo.readContent(p.getId()));
            } catch (Exception e) {
                // 单条内容读失败不应中断整体导出，降级为空内容。
                o.put("content", "");
            }
            arr.put(o);
        }
        return arr.toString(2);
    }

    // 导入（追加，不覆盖现有）。先全量解析+校验到内存，再由仓储一次性事务落盘。返回导入数量。
    public static int importJson(HostProfileRepository repo, String json) throws Exception {
        if (json == null) throw new IllegalArgumentException("json is null");
        if (json.length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("Import JSON too large: " + json.length() + " chars");
        }
        JSONArray arr = new JSONArray(json);
        if (arr.length() > MAX_PROFILES) {
            throw new IllegalArgumentException("Too many profiles: " + arr.length());
        }
        List<HostProfileRepository.ImportItem> items = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String title = clamp(o.optString("title", "imported"), MAX_TITLE_CHARS);
            String sourceType = whitelistType(o.optString("sourceType", HostProfile.TYPE_NEW));
            String sourceRef = o.isNull("sourceRef") ? null : o.optString("sourceRef", null);
            String content = o.optString("content", "");
            if (content.length() > MAX_CONTENT_CHARS) {
                throw new IllegalArgumentException("Profile content too large at index " + i);
            }
            boolean enabled = o.optBoolean("enabled", true);
            items.add(new HostProfileRepository.ImportItem(title, enabled, sourceType, sourceRef, content));
        }
        repo.importProfiles(items);
        return items.size();
    }

    private static String clamp(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) : s;
    }

    // sourceType 白名单：非法值（含 TSV 注入构造）归一为 NEW，杜绝注入与未知类型。
    private static String whitelistType(String t) {
        if (HostProfile.TYPE_FILE.equals(t) || HostProfile.TYPE_URL.equals(t)) {
            return t;
        }
        return HostProfile.TYPE_NEW;
    }
}
