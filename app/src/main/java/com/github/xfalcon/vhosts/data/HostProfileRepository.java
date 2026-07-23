package com.github.xfalcon.vhosts.data;

import com.github.xfalcon.vhosts.model.HostProfile;
import com.github.xfalcon.vhosts.util.LogUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class HostProfileRepository {
    private static final String TAG = "HostProfileRepository";
    private static final String INDEX_FILE = "index.tsv";

    // 无状态：仅持有目录路径，每次读操作都从磁盘重读，
    // 保证 Activity / Adapter / EditActivity 各自 new 的多个仓储实例始终看到最新数据。
    private final File profilesDir;

    // 注意：构造参数直接作为方案目录使用（不再内部追加 "profiles"）。
    // 调用方应传入 new File(context.getFilesDir(), "profiles")，最终数据落在 filesDir/profiles/。
    public HostProfileRepository(File profilesDir) {
        this.profilesDir = profilesDir;
        if (!profilesDir.exists()) {
            profilesDir.mkdirs();
        }
    }

    private List<HostProfile> loadIndex() {
        List<HostProfile> list = new ArrayList<>();
        File indexFile = new File(profilesDir, INDEX_FILE);
        if (!indexFile.exists()) {
            return list;
        }
        try {
            String content = readFileContent(indexFile);
            for (String line : content.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] fields = line.split("\t");
                if (fields.length < 5) {
                    LogUtils.w(TAG, "Invalid index line (< 5 fields): " + line);
                    continue;
                }
                try {
                    String id = fields[0];
                    String title = fields[1];
                    boolean enabled = Boolean.parseBoolean(fields[2]);
                    int order = Integer.parseInt(fields[3]);
                    String sourceType = fields[4];
                    String sourceRef = (fields.length > 5 && !fields[5].isEmpty()) ? fields[5] : null;
                    list.add(HostProfile.create(id, title, enabled, order, sourceType, sourceRef));
                } catch (Exception e) {
                    LogUtils.w(TAG, "Failed to parse index line: " + line, e);
                }
            }
            Collections.sort(list, (a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
        } catch (Exception e) {
            LogUtils.e(TAG, "Failed to load index", e);
        }
        return list;
    }

    // 写失败必须向上抛（调用方 create/updateMeta/delete 都声明 throws），不静默吞掉。
    private void saveIndex(List<HostProfile> list) throws IOException {
        File indexFile = new File(profilesDir, INDEX_FILE);
        StringBuilder sb = new StringBuilder();
        for (HostProfile p : list) {
            sb.append(p.getId()).append("\t")
              .append(sanitize(p.getTitle())).append("\t")
              .append(p.isEnabled()).append("\t")
              .append(p.getOrder()).append("\t")
              .append(p.getSourceType()).append("\t")
              .append(p.getSourceRef() != null ? sanitize(p.getSourceRef()) : "").append("\n");
        }
        writeFileContent(indexFile, sb.toString());
    }

    // 标题/来源可能来自用户输入或文件名，剔除会破坏 TSV 行结构的字符。
    private static String sanitize(String s) {
        return s.replace("\t", " ").replace("\n", " ").replace("\r", " ");
    }

    public List<HostProfile> findAll() {
        return loadIndex();
    }

    public List<HostProfile> findEnabled() {
        List<HostProfile> result = new ArrayList<>();
        for (HostProfile p : loadIndex()) {
            if (p.isEnabled()) {
                result.add(p);
            }
        }
        return result;
    }

    public HostProfile findById(String id) {
        for (HostProfile p : loadIndex()) {
            if (p.getId().equals(id)) {
                return p;
            }
        }
        return null;
    }

    public String readContent(String id) throws IOException {
        File contentFile = new File(profilesDir, id + ".hosts");
        if (!contentFile.exists()) {
            return "";
        }
        // 与 FileUtils.readFile 一致：读回的方案内容去除首尾空白（末尾换行等）。
        // hosts 按行解析，首尾空白无意义，trim 让读写往返可预期。
        return readFileContent(contentFile).trim();
    }

    public HostProfile create(String title, String sourceType, String sourceRef, String content) throws IOException {
        List<HostProfile> list = loadIndex();
        int maxOrder = -1;
        for (HostProfile p : list) {
            if (p.getOrder() > maxOrder) maxOrder = p.getOrder();
        }
        String id = UUID.randomUUID().toString();
        HostProfile p = HostProfile.create(id, title, true, maxOrder + 1, sourceType, sourceRef);
        writeHostContent(id, content == null ? "" : content);
        list.add(p);
        saveIndex(list);
        return p;
    }

    public void updateMeta(HostProfile updated) throws IOException {
        List<HostProfile> list = loadIndex();
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(updated.getId())) {
                list.set(i, updated);
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IOException("Profile not found: " + updated.getId());
        }
        saveIndex(list);
    }

    public void updateContent(String id, String content) throws IOException {
        writeHostContent(id, content == null ? "" : content);
    }

    public void delete(String id) throws IOException {
        List<HostProfile> list = loadIndex();
        List<HostProfile> kept = new ArrayList<>();
        for (HostProfile p : list) {
            if (!p.getId().equals(id)) kept.add(p);
        }
        File contentFile = new File(profilesDir, id + ".hosts");
        if (contentFile.exists()) {
            contentFile.delete();
        }
        saveIndex(kept);
    }

    private void writeHostContent(String id, String content) throws IOException {
        File contentFile = new File(profilesDir, id + ".hosts");
        writeFileContent(contentFile, content);
    }

    private String readFileContent(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        try {
            int offset = 0;
            int read;
            while (offset < bytes.length && (read = fis.read(bytes, offset, bytes.length - offset)) != -1) {
                offset += read;
            }
        } finally {
            fis.close();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void writeFileContent(File file, String content) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        try {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            fos.close();
        }
    }
}
