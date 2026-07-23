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
    // 单个索引/内容文件大小上限（32MB），防御被篡改的超大文件一次性读入导致 OOM。
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    // 索引读改写跨所有仓储实例串行化：Activity/Adapter/EditActivity 各 new 一个实例操作同一磁盘，
    // 无锁的 load→modify→save 序列会相互覆盖（lost update）。用类级锁互斥所有写操作。
    private static final Object INDEX_LOCK = new Object();

    // 无状态：仅持有目录路径，每次读操作都从磁盘重读，
    // 保证各处 new 的多个仓储实例始终看到最新数据。
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
        // 读操作不加锁：saveIndex 通过临时文件+rename 原子替换，读到的必是完整的旧或新文件。
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
                    if (!isValidId(id)) {
                        LogUtils.w(TAG, "Skip index line with invalid id: " + id);
                        continue;
                    }
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
            sb.append(sanitize(p.getId())).append("\t")
              .append(sanitize(p.getTitle())).append("\t")
              .append(p.isEnabled()).append("\t")
              .append(p.getOrder()).append("\t")
              .append(sanitize(p.getSourceType())).append("\t")
              .append(p.getSourceRef() != null ? sanitize(p.getSourceRef()) : "").append("\n");
        }
        writeFileContent(indexFile, sb.toString());
    }

    // 标题/来源/类型可能来自用户输入或导入 JSON，剔除会破坏 TSV 行结构的字符（防注入/串行化损坏）。
    private static String sanitize(String s) {
        if (s == null) return "";
        return s.replace("\t", " ").replace("\n", " ").replace("\r", " ");
    }

    // id 用于拼接文件名（<id>.hosts），禁止路径分隔符/遍历，纵深防御（即便 index.tsv 被篡改）。
    private static boolean isValidId(String id) {
        return id != null && !id.isEmpty() && id.length() <= 128
                && !id.contains("/") && !id.contains("\\")
                && !id.contains("..") && !id.equals(".");
    }

    private static void requireValidId(String id) {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("Invalid profile id: " + id);
        }
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
        requireValidId(id);
        File contentFile = new File(profilesDir, id + ".hosts");
        if (!contentFile.exists()) {
            return "";
        }
        // 与 FileUtils.readFile 一致：读回的方案内容去除首尾空白（末尾换行等）。
        // hosts 按行解析，首尾空白无意义，trim 让读写往返可预期。
        return readFileContent(contentFile).trim();
    }

    public HostProfile create(String title, String sourceType, String sourceRef, String content) throws IOException {
        synchronized (INDEX_LOCK) {
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
    }

    // 批量导入：先逐条写内容文件，再一次性 saveIndex；索引写成功才算导入成功。
    // 任一步失败则回滚已写内容文件并整体抛出——避免"导入一半"的不一致（HIGH）。
    public List<HostProfile> importProfiles(List<ImportItem> items) throws IOException {
        synchronized (INDEX_LOCK) {
            List<HostProfile> list = loadIndex();
            int maxOrder = -1;
            for (HostProfile p : list) {
                if (p.getOrder() > maxOrder) maxOrder = p.getOrder();
            }
            List<HostProfile> added = new ArrayList<>();
            List<String> writtenIds = new ArrayList<>();
            try {
                for (ImportItem it : items) {
                    String id = UUID.randomUUID().toString();
                    writeHostContent(id, it.content == null ? "" : it.content);
                    writtenIds.add(id);
                    HostProfile p = HostProfile.create(id, it.title, it.enabled, ++maxOrder,
                            it.sourceType, it.sourceRef);
                    list.add(p);
                    added.add(p);
                }
                saveIndex(list);
            } catch (IOException | RuntimeException e) {
                for (String id : writtenIds) {
                    new File(profilesDir, id + ".hosts").delete();
                }
                throw e;
            }
            return added;
        }
    }

    public void updateMeta(HostProfile updated) throws IOException {
        synchronized (INDEX_LOCK) {
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
    }

    public void updateContent(String id, String content) throws IOException {
        requireValidId(id);
        synchronized (INDEX_LOCK) {
            writeHostContent(id, content == null ? "" : content);
        }
    }

    public void delete(String id) throws IOException {
        requireValidId(id);
        synchronized (INDEX_LOCK) {
            List<HostProfile> list = loadIndex();
            List<HostProfile> kept = new ArrayList<>();
            for (HostProfile p : list) {
                if (!p.getId().equals(id)) kept.add(p);
            }
            // 先落索引，成功后再删内容文件：避免 saveIndex 失败导致"索引仍在、内容已删"的空白方案。
            saveIndex(kept);
            File contentFile = new File(profilesDir, id + ".hosts");
            if (contentFile.exists() && !contentFile.delete()) {
                LogUtils.w(TAG, "Failed to delete content file for " + id);
            }
        }
    }

    private void writeHostContent(String id, String content) throws IOException {
        requireValidId(id);
        File contentFile = new File(profilesDir, id + ".hosts");
        writeFileContent(contentFile, content);
    }

    private String readFileContent(File file) throws IOException {
        long len = file.length();
        if (len > MAX_FILE_BYTES) {
            throw new IOException("File too large (" + len + " bytes): " + file.getName());
        }
        byte[] bytes = new byte[(int) len];
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

    // 原子写：写入同目录临时文件 → flush+fsync → rename 覆盖目标。
    // 进程若在写入中被杀，损坏的只是 .tmp，正式文件仍是上一份完整内容（CRITICAL：防索引整体丢失）。
    private void writeFileContent(File file, String content) throws IOException {
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp);
        try {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.getFD().sync();
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(file)) {
            tmp.delete();
            throw new IOException("Atomic rename failed: " + tmp.getName() + " -> " + file.getName());
        }
    }

    // 批量导入的单条数据（id/order 由仓储生成）。
    public static final class ImportItem {
        public final String title;
        public final boolean enabled;
        public final String sourceType;
        public final String sourceRef;
        public final String content;

        public ImportItem(String title, boolean enabled, String sourceType, String sourceRef, String content) {
            this.title = title;
            this.enabled = enabled;
            this.sourceType = sourceType;
            this.sourceRef = sourceRef;
            this.content = content;
        }
    }
}
