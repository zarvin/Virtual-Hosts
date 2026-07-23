package com.github.xfalcon.vhosts.data;

import com.github.xfalcon.vhosts.model.HostProfile;

import org.json.JSONArray;
import org.json.JSONObject;

// 所有方案的导入/导出（JSON，含内容）。org.json 是 Android 运行时自带，仅在 App 内使用。
public class ImportExportHelper {

    public static String export(HostProfileRepository repo) throws Exception {
        JSONArray arr = new JSONArray();
        for (HostProfile p : repo.findAll()) {
            JSONObject o = new JSONObject();
            o.put("title", p.getTitle());
            o.put("enabled", p.isEnabled());
            o.put("sourceType", p.getSourceType());
            o.put("sourceRef", p.getSourceRef() == null ? JSONObject.NULL : p.getSourceRef());
            o.put("content", repo.readContent(p.getId()));
            arr.put(o);
        }
        return arr.toString(2);
    }

    // 导入（追加，不覆盖现有）。返回导入的方案数量。
    public static int importJson(HostProfileRepository repo, String json) throws Exception {
        JSONArray arr = new JSONArray(json);
        int count = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String title = o.optString("title", "imported");
            String sourceType = o.optString("sourceType", "NEW");
            String sourceRef = o.isNull("sourceRef") ? null : o.optString("sourceRef", null);
            String content = o.optString("content", "");
            HostProfile p = repo.create(title, sourceType, sourceRef, content);
            if (!o.optBoolean("enabled", true)) {
                repo.updateMeta(p.withEnabled(false));
            }
            count++;
        }
        return count;
    }
}
