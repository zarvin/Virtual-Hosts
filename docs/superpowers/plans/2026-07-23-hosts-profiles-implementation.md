# Hosts 方案列表实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 改造 Virtual Hosts 从单文件 hosts 管理升级为多方案列表，每个方案可独立启用/编辑，运行中可即时切换生效。

**Architecture:** 分层设计 — 数据模型 `HostProfile` + 纯逻辑仓储 `HostProfileRepository`（TSV 索引 + 每条内容文件）+ 改造 `DnsChange` 支持多方案合并与靠前优先 + UI 层用 RecyclerView 列表 + 编辑页 + 旧数据迁移。纯逻辑层严格 TDD（JVM 单测），UI 层编译验证 + 冒烟测试。

**Tech Stack:** JUnit 4, RecyclerView, androidx.preference, org.xbill.DNS（现有内置 dnsjava）

## Global Constraints

- 兼容底线：`minSdk 19`；新增 API 调用务必做版本判断。
- 不可变：数据对象改动返回新副本，不就地修改（`with*` 模式）。
- 错误处理：显式处理 IO 异常，不静默吞掉；输入验证在系统边界。
- 存储：元数据索引 `filesDir/profiles/index.tsv`（TSV 格式，零依赖）；内容 `filesDir/profiles/<id>.hosts`。
- 合并规则：靠前优先（用 `putIfAbsent`），同域名不同 IP 时列表越靠上的方案赢。
- 运行时重载：VPN 运行中改动开关或保存编辑 → 自动即时重建解析表，隧道不断。
- 迁移：首次运行新版，检测旧 `HOST_URI`/`net_hosts` 并转成第一个已启用方案（一次性，用 `PROFILES_MIGRATED` 标志）。
- 无新依赖：不引入 Gson、Moshi、Room、Robolectric；用纯 Java + 现有依赖。
- 字符串资源：新增文案全部本地化（英文 + 中文）。

---

## Task 1: 准备环境 & gradlew 权限修复

**Files:**
- Modify: `gradlew` (需修复可执行位)
- Modify: `app/build.gradle` (可选：加 RecyclerView 依赖)

**Interfaces:**
- Produces: 能正常运行 `sh gradlew testGithubDebugUnitTest`

- [ ] **Step 1: 检查 gradlew 权限**

```bash
cd /Users/zarvin/Documents/ProjectsAndroid/Virtual-Hosts
git ls-files -s gradlew    # 输出 100644 (非可执行)
```

- [ ] **Step 2: 修复权限**

```bash
git update-index --chmod=+x gradlew
git commit -m "fix: set gradlew as executable"
ls -la gradlew   # 应显示 -rwxr-xr-x
```

- [ ] **Step 3: 验证能正常跑 gradle 命令**

```bash
sh gradlew -v 2>&1 | head -5   # 应显示 "Gradle 8.13"
```

- [ ] **Step 4: 确认 RecyclerView 依赖**

在 `app/build.gradle` 的 `dependencies` 块中，检查是否已有 RecyclerView。若无，添加：

```gradle
implementation 'androidx.recyclerview:recyclerview:1.3.0'
```

然后 `sh gradlew assembleGithubDebug -x test` 编译验证。

---

## Task 2: 数据模型 `HostProfile`

**Files:**
- Create: `app/src/main/java/com/github/xfalcon/vhosts/model/HostProfile.java`
- Create: `app/src/test/java/com/github/xfalcon/vhosts/model/HostProfileTest.java`

**Interfaces:**
- Produces: `HostProfile` 类（不可变，id/title/enabled/order/sourceType/sourceRef）与工厂/比对方法

- [ ] **Step 1: 写测试**

```java
package com.github.xfalcon.vhosts.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class HostProfileTest {
    @Test
    public void createAndGetFields() {
        HostProfile p = HostProfile.create("abc-id", "My Hosts", true, 0, "NEW", null);
        assertEquals("abc-id", p.getId());
        assertEquals("My Hosts", p.getTitle());
        assertTrue(p.isEnabled());
        assertEquals(0, p.getOrder());
        assertEquals("NEW", p.getSourceType());
        assertNull(p.getSourceRef());
    }

    @Test
    public void immutableWithTitle() {
        HostProfile p1 = HostProfile.create("id1", "Old", true, 0, "NEW", null);
        HostProfile p2 = p1.withTitle("New");
        assertEquals("Old", p1.getTitle());
        assertEquals("New", p2.getTitle());
        assertEquals("id1", p2.getId());
    }

    @Test
    public void immutableWithEnabled() {
        HostProfile p1 = HostProfile.create("id1", "T", true, 0, "NEW", null);
        HostProfile p2 = p1.withEnabled(false);
        assertTrue(p1.isEnabled());
        assertFalse(p2.isEnabled());
    }

    @Test
    public void immutableWithOrder() {
        HostProfile p1 = HostProfile.create("id1", "T", true, 0, "NEW", null);
        HostProfile p2 = p1.withOrder(5);
        assertEquals(0, p1.getOrder());
        assertEquals(5, p2.getOrder());
    }

    @Test
    public void sourceTypeAndRef() {
        HostProfile p = HostProfile.create("id1", "T", true, 0, "URL", "https://example.com/hosts");
        assertEquals("URL", p.getSourceType());
        assertEquals("https://example.com/hosts", p.getSourceRef());
    }
}
```

Run: `sh gradlew testGithubDebugUnitTest --tests "com.github.xfalcon.vhosts.model.HostProfileTest" -v`

Expected: FAIL with "class not found"

- [ ] **Step 2: 实现 HostProfile**

```java
package com.github.xfalcon.vhosts.model;

public final class HostProfile {
    private final String id;
    private final String title;
    private final boolean enabled;
    private final int order;
    private final String sourceType;  // NEW | FILE | URL
    private final String sourceRef;   // null for NEW/FILE; URL string for URL

    private HostProfile(String id, String title, boolean enabled, int order, String sourceType, String sourceRef) {
        this.id = id;
        this.title = title;
        this.enabled = enabled;
        this.order = order;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
    }

    public static HostProfile create(String id, String title, boolean enabled, int order, String sourceType, String sourceRef) {
        return new HostProfile(id, title, enabled, order, sourceType, sourceRef);
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public boolean isEnabled() { return enabled; }
    public int getOrder() { return order; }
    public String getSourceType() { return sourceType; }
    public String getSourceRef() { return sourceRef; }

    public HostProfile withTitle(String newTitle) {
        return new HostProfile(id, newTitle, enabled, order, sourceType, sourceRef);
    }

    public HostProfile withEnabled(boolean newEnabled) {
        return new HostProfile(id, title, newEnabled, order, sourceType, sourceRef);
    }

    public HostProfile withOrder(int newOrder) {
        return new HostProfile(id, title, enabled, newOrder, sourceType, sourceRef);
    }

    @Override
    public String toString() {
        return "HostProfile{" + "id=" + id + ", title=" + title + ", enabled=" + enabled + 
               ", order=" + order + ", sourceType=" + sourceType + "}";
    }
}
```

- [ ] **Step 3: 跑测试验证通过**

```bash
sh gradlew testGithubDebugUnitTest --tests "com.github.xfalcon.vhosts.model.HostProfileTest" -v
```

Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/github/xfalcon/vhosts/model/HostProfile.java \
        app/src/test/java/com/github/xfalcon/vhosts/model/HostProfileTest.java
git commit -m "feat: add HostProfile immutable value object"
```

---

## Task 3: 仓储 `HostProfileRepository`

**Files:**
- Create: `app/src/main/java/com/github/xfalcon/vhosts/data/HostProfileRepository.java`
- Create: `app/src/test/java/com/github/xfalcon/vhosts/data/HostProfileRepositoryTest.java`
- Modify: `app/src/main/java/com/github/xfalcon/vhosts/util/FileUtils.java` (加 `readFile`)

**Interfaces:**
- Consumes: `HostProfile` (from Task 2)
- Produces: `HostProfileRepository.findAll()`, `findEnabled()`, `findById(id)`, `readContent(id)`, `create(title, sourceType, sourceRef, content)`, `updateMeta(HostProfile)`, `updateContent(id, content)`, `delete(id)`

- [ ] **Step 1: 补充 FileUtils.readFile**

```java
// app/src/main/java/com/github/xfalcon/vhosts/util/FileUtils.java
// 在现有 writeFile 后加:

public static String readFile(String filePath) throws Exception {
    java.io.File file = new java.io.File(filePath);
    java.io.FileInputStream fis = new java.io.FileInputStream(file);
    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(fis));
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = br.readLine()) != null) {
        sb.append(line).append("\n");
    }
    br.close();
    fis.close();
    return sb.toString().trim();
}
```

- [ ] **Step 2: 写仓储测试**

```java
package com.github.xfalcon.vhosts.data;

import com.github.xfalcon.vhosts.model.HostProfile;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.*;

public class HostProfileRepositoryTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HostProfileRepository repo;
    private File baseDir;

    @Before
    public void setUp() throws Exception {
        baseDir = tempFolder.newFolder("profiles");
        repo = new HostProfileRepository(baseDir);
    }

    @Test
    public void createAndFindAll() throws Exception {
        HostProfile p = repo.create("Test Hosts", "NEW", null, "127.0.0.1 example.com\n");
        assertNotNull(p.getId());
        assertTrue(p.isEnabled());
        assertEquals(0, p.getOrder());

        List<HostProfile> all = repo.findAll();
        assertEquals(1, all.size());
        assertEquals(p.getId(), all.get(0).getId());
    }

    @Test
    public void readContent() throws Exception {
        String content = "127.0.0.1 a.com\n127.0.0.1 b.com\n";
        HostProfile p = repo.create("Test", "NEW", null, content);
        String read = repo.readContent(p.getId());
        assertEquals(content.trim(), read);
    }

    @Test
    public void findEnabled() throws Exception {
        repo.create("P1", "NEW", null, "");
        repo.create("P2", "NEW", null, "");
        HostProfile p1 = repo.findAll().get(0);
        HostProfile p2 = repo.findAll().get(1);
        
        // 默认都启用
        List<HostProfile> enabled = repo.findEnabled();
        assertEquals(2, enabled.size());

        // 禁用第二个
        repo.updateMeta(p2.withEnabled(false));
        enabled = repo.findEnabled();
        assertEquals(1, enabled.size());
        assertEquals(p1.getId(), enabled.get(0).getId());
    }

    @Test
    public void updateMeta() throws Exception {
        HostProfile p = repo.create("Old", "NEW", null, "");
        HostProfile updated = p.withTitle("New").withOrder(5);
        repo.updateMeta(updated);

        HostProfile fetched = repo.findById(p.getId());
        assertEquals("New", fetched.getTitle());
        assertEquals(5, fetched.getOrder());
    }

    @Test
    public void updateContent() throws Exception {
        HostProfile p = repo.create("Test", "NEW", null, "old");
        repo.updateContent(p.getId(), "new content");
        String content = repo.readContent(p.getId());
        assertEquals("new content", content);
    }

    @Test
    public void delete() throws Exception {
        HostProfile p = repo.create("Test", "NEW", null, "");
        repo.delete(p.getId());
        List<HostProfile> all = repo.findAll();
        assertEquals(0, all.size());
    }

    @Test
    public void orderAndFindAll() throws Exception {
        HostProfile p1 = repo.create("P1", "NEW", null, "");
        HostProfile p2 = repo.create("P2", "NEW", null, "");
        // p1.order=0, p2.order=1
        List<HostProfile> all = repo.findAll();
        assertEquals(p1.getId(), all.get(0).getId());  // order 0 先
        assertEquals(p2.getId(), all.get(1).getId());  // order 1 后
    }
}
```

Run: `sh gradlew testGithubDebugUnitTest --tests "com.github.xfalcon.vhosts.data.HostProfileRepositoryTest" -v`

Expected: FAIL with "class not found"

- [ ] **Step 3: 实现 HostProfileRepository**

```java
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

    private File baseDir;
    private File profilesDir;
    private List<HostProfile> cache;

    public HostProfileRepository(File baseDir) {
        this.baseDir = baseDir;
        this.profilesDir = new File(baseDir, "profiles");
        if (!profilesDir.exists()) {
            profilesDir.mkdirs();
        }
        this.cache = new ArrayList<>();
        loadIndex();
    }

    private void loadIndex() {
        cache.clear();
        File indexFile = new File(profilesDir, INDEX_FILE);
        if (!indexFile.exists()) {
            return;
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
                    String sourceRef = fields.length > 5 ? fields[5] : null;
                    cache.add(HostProfile.create(id, title, enabled, order, sourceType, sourceRef));
                } catch (Exception e) {
                    LogUtils.w(TAG, "Failed to parse index line: " + line, e);
                }
            }
            // 按 order 排序
            Collections.sort(cache, (a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
        } catch (Exception e) {
            LogUtils.e(TAG, "Failed to load index", e);
        }
    }

    private void saveIndex() {
        File indexFile = new File(profilesDir, INDEX_FILE);
        StringBuilder sb = new StringBuilder();
        for (HostProfile p : cache) {
            sb.append(p.getId()).append("\t")
              .append(p.getTitle()).append("\t")
              .append(p.isEnabled()).append("\t")
              .append(p.getOrder()).append("\t")
              .append(p.getSourceType()).append("\t")
              .append(p.getSourceRef() != null ? p.getSourceRef() : "").append("\n");
        }
        try {
            writeFileContent(indexFile, sb.toString());
        } catch (IOException e) {
            LogUtils.e(TAG, "Failed to save index", e);
        }
    }

    public List<HostProfile> findAll() {
        return new ArrayList<>(cache);
    }

    public List<HostProfile> findEnabled() {
        List<HostProfile> result = new ArrayList<>();
        for (HostProfile p : cache) {
            if (p.isEnabled()) {
                result.add(p);
            }
        }
        return result;
    }

    public HostProfile findById(String id) {
        for (HostProfile p : cache) {
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
        return readFileContent(contentFile);
    }

    public HostProfile create(String title, String sourceType, String sourceRef, String content) throws IOException {
        String id = UUID.randomUUID().toString();
        int nextOrder = cache.isEmpty() ? 0 : cache.get(cache.size() - 1).getOrder() + 1;
        HostProfile p = HostProfile.create(id, title, true, nextOrder, sourceType, sourceRef);
        cache.add(p);
        Collections.sort(cache, (a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
        writeHostContent(id, content);
        saveIndex();
        return p;
    }

    public void updateMeta(HostProfile updated) throws IOException {
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).getId().equals(updated.getId())) {
                cache.set(i, updated);
                break;
            }
        }
        Collections.sort(cache, (a, b) -> Integer.compare(a.getOrder(), b.getOrder()));
        saveIndex();
    }

    public void updateContent(String id, String content) throws IOException {
        writeHostContent(id, content);
    }

    public void delete(String id) throws IOException {
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).getId().equals(id)) {
                cache.remove(i);
                break;
            }
        }
        File contentFile = new File(profilesDir, id + ".hosts");
        if (contentFile.exists()) {
            contentFile.delete();
        }
        saveIndex();
    }

    private void writeHostContent(String id, String content) throws IOException {
        File contentFile = new File(profilesDir, id + ".hosts");
        writeFileContent(contentFile, content);
    }

    private String readFileContent(File file) throws IOException {
        byte[] bytes = new byte[(int) file.length()];
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        fis.read(bytes);
        fis.close();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void writeFileContent(File file, String content) throws IOException {
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(content.getBytes(StandardCharsets.UTF_8));
        fos.close();
    }
}
```

- [ ] **Step 4: 跑测试验证**

```bash
sh gradlew testGithubDebugUnitTest --tests "com.github.xfalcon.vhosts.data.HostProfileRepositoryTest" -v
```

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/github/xfalcon/vhosts/data/HostProfileRepository.java \
        app/src/test/java/com/github/xfalcon/vhosts/data/HostProfileRepositoryTest.java \
        app/src/main/java/com/github/xfalcon/vhosts/util/FileUtils.java
git commit -m "feat: add HostProfileRepository (TSV index + content files)"
```

---

## Task 4: 改造 `DnsChange` 支持多方案合并

**Files:**
- Modify: `app/src/main/java/com/github/xfalcon/vhosts/vservice/DnsChange.java`
- Create: `app/src/test/java/com/github/xfalcon/vhosts/vservice/DnsChangeTest.java`

**Interfaces:**
- Consumes: `handle_hosts(InputStream)` 保留
- Produces: `loadProfiles(List<String> contentsInOrder)` (新方法，支持靠前优先的多方案合并)；`DOMAINS_IP_MAPS4/6` 改为 `volatile`

- [ ] **Step 1: 写测试（多方案合并 + 靠前优先）**

```java
package com.github.xfalcon.vhosts.vservice;

import org.junit.Test;
import org.xbill.DNS.Address;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class DnsChangeTest {

    @Test
    public void singleProfileParsing() throws Exception {
        String content = "127.0.0.1 a.com\n127.0.0.1 b.com\n";
        List<String> profiles = new ArrayList<>();
        profiles.add(content);
        DnsChange.loadProfiles(profiles);
        
        // 验证通过查表来间接验证（实际项目中会通过 UDPOutput 查表）
        // 这里我们直接访问 DOMAINS_IP_MAPS4（虽然是 private，单测可以用反射或 getter）
        // 简化起见，我们在 DnsChange 加公开 getter
        assertTrue("Should have parsed records", DnsChange.hasRecords());
    }

    @Test
    public void multipleProfilesMergePriority() throws Exception {
        // Profile 1 (靠前，优先级高)
        String profile1 = "127.0.0.1 example.com\n";
        // Profile 2 (靠后，优先级低)
        String profile2 = "192.168.1.1 example.com\n127.0.0.1 other.com\n";
        
        List<String> profiles = new ArrayList<>();
        profiles.add(profile1);
        profiles.add(profile2);
        
        DnsChange.loadProfiles(profiles);
        
        // example.com 应该映射到 Profile 1 的 127.0.0.1（靠前优先）
        // other.com 应该映射到 Profile 2 的 127.0.0.1
        assertEquals("Profile 1 IP should win for example.com", "127.0.0.1", DnsChange.lookup("example.com."));
        assertEquals("Other.com from Profile 2", "127.0.0.1", DnsChange.lookup("other.com."));
    }

    @Test
    public void wildcardSuffixMatching() throws Exception {
        String content = "127.0.0.1 .example.com\n";
        List<String> profiles = new ArrayList<>();
        profiles.add(content);
        DnsChange.loadProfiles(profiles);
        
        // .example.com 应该匹配 a.example.com
        assertEquals("127.0.0.1", DnsChange.lookup("a.example.com."));
    }

    @Test
    public void ipv4And ipv6Separation() throws Exception {
        String content = "127.0.0.1 a.com\n2001:db8::1 b.com\n";
        List<String> profiles = new ArrayList<>();
        profiles.add(content);
        DnsChange.loadProfiles(profiles);
        
        // A 记录和 AAAA 记录应该分别存在
        assertTrue("IPv4 records should be loaded", DnsChange.hasIPv4Records());
        assertTrue("IPv6 records should be loaded", DnsChange.hasIPv6Records());
    }
}
```

- [ ] **Step 2: 修改 DnsChange 实现**

打开 `app/src/main/java/com/github/xfalcon/vhosts/vservice/DnsChange.java`，找到：

```java
static ConcurrentHashMap<String, String> DOMAINS_IP_MAPS4 = null;
static ConcurrentHashMap<String, String> DOMAINS_IP_MAPS6 = null;
```

改为：

```java
static volatile ConcurrentHashMap<String, String> DOMAINS_IP_MAPS4 = null;
static volatile ConcurrentHashMap<String, String> DOMAINS_IP_MAPS6 = null;
```

在 `DnsChange` 类中添加新方法 `loadProfiles` 和测试辅助方法：

```java
public static void loadProfiles(List<String> contentsInOrder) {
    ConcurrentHashMap<String, String> map4 = new ConcurrentHashMap<>();
    ConcurrentHashMap<String, String> map6 = new ConcurrentHashMap<>();
    
    // 按传入顺序（靠前优先）解析每个方案
    for (String content : contentsInOrder) {
        try {
            parseAndMerge(content, map4, map6);
        } catch (Exception e) {
            LogUtils.e(TAG, "Error parsing profile", e);
        }
    }
    
    // 原子替换
    DOMAINS_IP_MAPS4 = map4;
    DOMAINS_IP_MAPS6 = map6;
    LogUtils.d(TAG, "Loaded profiles: " + map4.size() + " IPv4, " + map6.size() + " IPv6");
}

private static void parseAndMerge(String content, ConcurrentHashMap<String, String> map4, ConcurrentHashMap<String, String> map6) throws Exception {
    String STR_COMMENT = "#";
    String HOST_PATTERN_STR = "^\\s*(" + STR_COMMENT + "?)\\s*(\\S*)\\s*([^" + STR_COMMENT + "]*)" + STR_COMMENT + "?(.*)$";
    java.util.regex.Pattern HOST_PATTERN = java.util.regex.Pattern.compile(HOST_PATTERN_STR);
    
    java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.StringReader(content));
    String line;
    while ((line = reader.readLine()) != null) {
        if (line.length() > 1000 || line.startsWith(STR_COMMENT)) continue;
        java.util.regex.Matcher matcher = HOST_PATTERN.matcher(line);
        if (matcher.find()) {
            String ip = matcher.group(2).trim();
            try {
                Address.getByAddress(ip);
            } catch (Exception e) {
                continue;
            }
            String domain = matcher.group(3).trim() + ".";
            if (ip.contains(":")) {
                map6.putIfAbsent(domain, ip);  // 靠前优先
            } else {
                map4.putIfAbsent(domain, ip);  // 靠前优先
            }
        }
    }
    reader.close();
}

// 测试辅助方法（JUnit 访问用）
public static boolean hasRecords() {
    return DOMAINS_IP_MAPS4 != null && !DOMAINS_IP_MAPS4.isEmpty();
}

public static boolean hasIPv4Records() {
    return DOMAINS_IP_MAPS4 != null && !DOMAINS_IP_MAPS4.isEmpty();
}

public static boolean hasIPv6Records() {
    return DOMAINS_IP_MAPS6 != null && !DOMAINS_IP_MAPS6.isEmpty();
}

public static String lookup(String domain) {
    if (DOMAINS_IP_MAPS4 == null) return null;
    if (DOMAINS_IP_MAPS4.containsKey(domain)) {
        return DOMAINS_IP_MAPS4.get(domain);
    }
    // 后缀匹配（与现有逻辑一致）
    domain = "." + domain;
    int j = 0;
    while (true) {
        int i = domain.indexOf(".", j);
        if (i == -1) return null;
        String suffix = domain.substring(i);
        if (".".equals(suffix) || "".equals(suffix)) return null;
        if (DOMAINS_IP_MAPS4.containsKey(suffix)) {
            return DOMAINS_IP_MAPS4.get(suffix);
        }
        j = i + 1;
    }
}
```

- [ ] **Step 3: 跑测试验证**

```bash
sh gradlew testGithubDebugUnitTest --tests "com.github.xfalcon.vhosts.vservice.DnsChangeTest" -v
```

Expected: PASS

- [ ] **Step 4: 提交**

```bash
git add app/src/main/java/com/github/xfalcon/vhosts/vservice/DnsChange.java \
        app/src/test/java/com/github/xfalcon/vhosts/vservice/DnsChangeTest.java
git commit -m "feat: support multi-profile merge with front-priority in DnsChange"
```

---

## Task 5: 运行时重载入口 & 迁移逻辑

**Files:**
- Create: `app/src/main/java/com/github/xfalcon/vhosts/data/HostsLoader.java`
- Create: `app/src/main/java/com/github/xfalcon/vhosts/data/MigrationHelper.java`
- Modify: `app/src/main/java/com/github/xfalcon/vhosts/vservice/VhostsService.java` (`setupHostFile` 改用仓储)
- Create: `app/src/test/java/com/github/xfalcon/vhosts/data/MigrationHelperTest.java`

**Interfaces:**
- Consumes: `HostProfileRepository`, `DnsChange.loadProfiles`
- Produces: `HostsLoader.reloadIfRunning(Context)` (运行时重载)；`MigrationHelper.migrateIfNeeded(Context)` (一次性迁移)

- [ ] **Step 1: 写迁移测试**

```java
package com.github.xfalcon.vhosts.data;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.*;

public class MigrationHelperTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private HostProfileRepository repo;
    private File baseDir;

    @Before
    public void setUp() throws Exception {
        baseDir = tempFolder.newFolder("profiles");
        repo = new HostProfileRepository(baseDir);
    }

    @Test
    public void noMigrationIfAlreadyDone() throws Exception {
        // 模拟已迁移状态
        File migrationFlag = new File(baseDir, ".profiles_migrated");
        migrationFlag.createNewFile();
        
        HostsLoader.migrateIfNeeded(repo, baseDir, "uri://old.txt", false, "net_hosts_content");
        
        // 不应创建新方案
        assertEquals(0, repo.findAll().size());
    }

    @Test
    public void migrateFromUri() throws Exception {
        String oldContent = "127.0.0.1 old.example.com\n";
        
        HostsLoader.migrateIfNeeded(repo, baseDir, "uri://old.txt", false, oldContent);
        
        java.util.List<com.github.xfalcon.vhosts.model.HostProfile> all = repo.findAll();
        assertEquals(1, all.size());
        assertEquals("导入的 hosts", all.get(0).getTitle());
        assertTrue(all.get(0).isEnabled());
    }

    @Test
    public void migrateFromNetHosts() throws Exception {
        String netContent = "127.0.0.1 net.example.com\n";
        
        HostsLoader.migrateIfNeeded(repo, baseDir, null, true, netContent);
        
        java.util.List<com.github.xfalcon.vhosts.model.HostProfile> all = repo.findAll();
        assertEquals(1, all.size());
        assertEquals("导入的 hosts", all.get(0).getTitle());
    }
}
```

- [ ] **Step 2: 实现 HostsLoader 和 MigrationHelper**

创建 `app/src/main/java/com/github/xfalcon/vhosts/data/HostsLoader.java`：

```java
package com.github.xfalcon.vhosts.data;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.github.xfalcon.vhosts.util.LogUtils;
import com.github.xfalcon.vhosts.vservice.DnsChange;
import com.github.xfalcon.vhosts.vservice.VhostsService;

import java.io.File;
import java.util.List;
import java.util.concurrent.Executors;

public class HostsLoader {
    private static final String TAG = "HostsLoader";

    // 运行时重载：从仓储读已启用方案，合并并加载到 DnsChange
    public static void reloadIfRunning(final Context context) {
        if (!VhostsService.isRunning()) {
            return;
        }
        
        // 后台线程执行，避免阻塞 UI
        Executors.newSingleThreadExecutor().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    HostProfileRepository repo = new HostProfileRepository(new File(context.getFilesDir(), "profiles"));
                    java.util.List<com.github.xfalcon.vhosts.model.HostProfile> enabled = repo.findEnabled();
                    
                    if (enabled.isEmpty()) {
                        LogUtils.d(TAG, "No enabled profiles to load");
                        return;
                    }
                    
                    java.util.List<String> contents = new java.util.ArrayList<>();
                    for (com.github.xfalcon.vhosts.model.HostProfile p : enabled) {
                        String content = repo.readContent(p.getId());
                        contents.add(content);
                    }
                    
                    DnsChange.loadProfiles(contents);
                    LogUtils.i(TAG, "Reloaded " + enabled.size() + " profiles");
                } catch (Exception e) {
                    LogUtils.e(TAG, "Error reloading profiles", e);
                }
            }
        });
    }

    // 一次性迁移旧数据
    public static void migrateIfNeeded(HostProfileRepository repo, File baseDir, String hostUri, boolean isNet, String content) throws Exception {
        File migrationFlag = new File(baseDir, ".profiles_migrated");
        if (migrationFlag.exists()) {
            LogUtils.d(TAG, "Migration already done");
            return;
        }

        if (content != null && !content.isEmpty()) {
            String sourceType = isNet ? "URL" : "FILE";
            String sourceRef = isNet ? null : hostUri;  // 实际上 FILE 源也不记 ref
            repo.create("导入的 hosts", sourceType, sourceRef, content);
            LogUtils.i(TAG, "Migrated legacy hosts to first profile");
        }

        migrationFlag.createNewFile();
    }
}
```

- [ ] **Step 3: 修改 VhostsService.setupHostFile()**

打开 `VhostsService.java`，找到 `setupHostFile()` 方法，替换为：

```java
private void setupHostFile() {
    try {
        File profilesDir = new File(getFilesDir(), "profiles");
        final HostProfileRepository repo = new HostProfileRepository(profilesDir);
        
        new Thread() {
            public void run() {
                try {
                    java.util.List<com.github.xfalcon.vhosts.model.HostProfile> enabled = repo.findEnabled();
                    if (enabled.isEmpty()) {
                        Looper.prepare();
                        Toast.makeText(getApplicationContext(), R.string.no_profiles_enabled, Toast.LENGTH_LONG).show();
                        Looper.loop();
                        return;
                    }
                    
                    java.util.List<String> contents = new java.util.ArrayList<>();
                    for (com.github.xfalcon.vhosts.model.HostProfile p : enabled) {
                        String content = repo.readContent(p.getId());
                        contents.add(content);
                    }
                    
                    DnsChange.loadProfiles(contents);
                    LogUtils.i(TAG, "Loaded " + enabled.size() + " profiles");
                } catch (Exception e) {
                    LogUtils.e(TAG, "Error loading profiles", e);
                }
            }
        }.start();
    } catch (Exception e) {
        LogUtils.e(TAG, "error setup host file service", e);
    }
}
```

同时在文件顶部加 import：
```java
import com.github.xfalcon.vhosts.data.HostProfileRepository;
import com.github.xfalcon.vhosts.data.HostsLoader;
```

- [ ] **Step 4: 跑迁移测试**

```bash
sh gradlew testGithubDebugUnitTest --tests "com.github.xfalcon.vhosts.data.MigrationHelperTest" -v
```

Expected: PASS

- [ ] **Step 5: 编译验证**

```bash
sh gradlew assembleGithubDebug -x test 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add app/src/main/java/com/github/xfalcon/vhosts/data/HostsLoader.java \
        app/src/main/java/com/github/xfalcon/vhosts/vservice/VhostsService.java \
        app/src/test/java/com/github/xfalcon/vhosts/data/MigrationHelperTest.java
git commit -m "feat: add runtime reload and legacy migration logic"
```

---

## Task 6: 新增字符串资源

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`
- Modify: `app/src/main/res/values-vi/strings.xml`

**Interfaces:**
- Produces: 新字符串键（列表为空、添加菜单、编辑、删除、重命名等）供 UI 层使用

- [ ] **Step 1: 添加到 values/strings.xml**

在 `</resources>` 前加：

```xml
    <string name="host_profiles_title">Host Profiles</string>
    <string name="no_profiles">No profiles yet. Add one to get started.</string>
    <string name="add_profile">Add Profile</string>
    <string name="add_new">Create New</string>
    <string name="add_from_file">Import from File</string>
    <string name="add_from_url">Download from URL</string>
    <string name="edit">Edit</string>
    <string name="delete">Delete</string>
    <string name="rename">Rename</string>
    <string name="save">Save</string>
    <string name="cancel">Cancel</string>
    <string name="title">Title</string>
    <string name="no_profiles_enabled">No profiles enabled. Please enable at least one profile.</string>
    <string name="confirm_delete">Delete this profile?</string>
    <string name="launch">Launch</string>
    <string name="stop">Stop</string>
    <string name="enter_title">Enter profile name</string>
    <string name="invalid_url">Invalid URL format</string>
    <string name="download_in_progress">Downloading...</string>
    <string name="records_count">%d records loaded</string>
```

- [ ] **Step 2: 添加到 values-zh/strings.xml**

在 `</resources>` 前加：

```xml
    <string name="host_profiles_title">主机方案</string>
    <string name="no_profiles">暂无方案，点击添加开始使用</string>
    <string name="add_profile">添加方案</string>
    <string name="add_new">新建空白</string>
    <string name="add_from_file">从文件导入</string>
    <string name="add_from_url">从URL下载</string>
    <string name="edit">编辑</string>
    <string name="delete">删除</string>
    <string name="rename">重命名</string>
    <string name="save">保存</string>
    <string name="cancel">取消</string>
    <string name="title">标题</string>
    <string name="no_profiles_enabled">未启用任何方案，请至少启用一个</string>
    <string name="confirm_delete">删除此方案？</string>
    <string name="launch">启动</string>
    <string name="stop">停止</string>
    <string name="enter_title">输入方案名称</string>
    <string name="invalid_url">URL格式不正确</string>
    <string name="download_in_progress">下载中...</string>
    <string name="records_count">已加载 %d 条记录</string>
```

- [ ] **Step 3: 添加到其他语言文件**

类似地添加到 `values-zh-rTW` 和 `values-vi`（简体中文对应繁体和越南语版本）

- [ ] **Step 4: 编译验证**

```bash
sh gradlew assembleGithubDebug -x test 2>&1 | grep -E "BUILD|ERROR" | head -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/res/values-zh/strings.xml \
        app/src/main/res/values-zh-rTW/strings.xml \
        app/src/main/res/values-vi/strings.xml
git commit -m "feat: add strings for multi-profile UI"
```

---

## Task 7: 列表项布局 & RecyclerView Adapter

**Files:**
- Create: `app/src/main/res/layout/item_host_profile.xml`
- Create: `app/src/main/java/com/github/xfalcon/vhosts/ui/HostListAdapter.java`

**Interfaces:**
- Consumes: `HostProfile`（数据）、`HostsLoader.reloadIfRunning`（运行时重载触发器）
- Produces: `HostListAdapter.setProfiles(List)`, `setOnProfileChangeListener`, `notifyDataSetChanged()`

- [ ] **Step 1: 创建列表项布局**

`app/src/main/res/layout/item_host_profile.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="16dp"
    android:gravity="center_vertical">

    <TextView
        android:id="@+id/profile_title"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textSize="16sp"
        android:textStyle="bold" />

    <Switch
        android:id="@+id/profile_switch"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginStart="16dp" />
</LinearLayout>
```

- [ ] **Step 2: 创建 RecyclerView Adapter**

`app/src/main/java/com/github/xfalcon/vhosts/ui/HostListAdapter.java`：

```java
package com.github.xfalcon.vhosts.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.github.xfalcon.vhosts.R;
import com.github.xfalcon.vhosts.model.HostProfile;
import com.github.xfalcon.vhosts.data.HostProfileRepository;
import com.github.xfalcon.vhosts.data.HostsLoader;
import com.github.xfalcon.vhosts.util.LogUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class HostListAdapter extends RecyclerView.Adapter<HostListAdapter.ViewHolder> {
    private static final String TAG = "HostListAdapter";
    
    private List<HostProfile> profiles = new ArrayList<>();
    private Context context;
    private HostProfileRepository repo;
    private OnProfileClickListener listener;

    public interface OnProfileClickListener {
        void onProfileClick(HostProfile profile);
    }

    public HostListAdapter(Context context, File profilesDir) {
        this.context = context;
        this.repo = new HostProfileRepository(profilesDir);
        this.profiles = repo.findAll();
    }

    public void setOnProfileClickListener(OnProfileClickListener listener) {
        this.listener = listener;
    }

    public void setProfiles(List<HostProfile> newProfiles) {
        this.profiles = newProfiles;
        notifyDataSetChanged();
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        android.view.View v = LayoutInflater.from(context)
            .inflate(R.layout.item_host_profile, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        final HostProfile profile = profiles.get(position);
        holder.titleView.setText(profile.getTitle());
        holder.switchView.setChecked(profile.isEnabled());
        
        // 点击条目进编辑页
        holder.itemView.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (listener != null) {
                    listener.onProfileClick(profile);
                }
            }
        });
        
        // 开关变化触发更新和重载
        holder.switchView.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                try {
                    HostProfile updated = profile.withEnabled(isChecked);
                    repo.updateMeta(updated);
                    HostsLoader.reloadIfRunning(context);
                    LogUtils.d(TAG, "Profile " + profile.getId() + " enabled=" + isChecked);
                } catch (Exception e) {
                    LogUtils.e(TAG, "Error updating profile", e);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return profiles.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView titleView;
        Switch switchView;

        ViewHolder(android.view.View itemView) {
            super(itemView);
            titleView = itemView.findViewById(R.id.profile_title);
            switchView = itemView.findViewById(R.id.profile_switch);
        }
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
sh gradlew assembleGithubDebug -x test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/res/layout/item_host_profile.xml \
        app/src/main/java/com/github/xfalcon/vhosts/ui/HostListAdapter.java
git commit -m "feat: add HostListAdapter and list item layout"
```

---

## Task 8: 编辑页 Activity

**Files:**
- Create: `app/src/main/res/layout/activity_host_edit.xml`
- Create: `app/src/main/java/com/github/xfalcon/vhosts/HostEditActivity.java`

**Interfaces:**
- Consumes: `HostProfile` ID（via Intent extra）、`HostProfileRepository`
- Produces: 点击保存后返回主界面并重载

- [ ] **Step 1: 创建编辑页布局**

`app/src/main/res/layout/activity_host_edit.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:padding="16dp">

        <ImageButton
            android:id="@+id/btn_back"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:background="?attr/selectableItemBackground"
            android:src="@drawable/ic_back"
            android:contentDescription="@string/cancel" />

        <EditText
            android:id="@+id/edit_title"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginStart="16dp"
            android:hint="@string/title"
            android:inputType="text" />

        <Button
            android:id="@+id/btn_save"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="16dp"
            android:text="@string/save" />
    </LinearLayout>

    <EditText
        android:id="@+id/edit_content"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:padding="16dp"
        android:hint="@string/info"
        android:inputType="textMultiLine"
        android:fontFamily="monospace"
        android:background="@android:color/white" />
</LinearLayout>
```

> **注** : `@drawable/ic_back` 是假设使用系统返回图标。实际可用 `android:text="←"` 或使用现有的 fab 图标变种。

- [ ] **Step 2: 创建编辑页 Activity**

`app/src/main/java/com/github/xfalcon/vhosts/HostEditActivity.java`：

```java
package com.github.xfalcon.vhosts;

import android.content.Intent;
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
```

- [ ] **Step 3: 编译验证**

```bash
sh gradlew assembleGithubDebug -x test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/res/layout/activity_host_edit.xml \
        app/src/main/java/com/github/xfalcon/vhosts/HostEditActivity.java
git commit -m "feat: add HostEditActivity for editing profiles"
```

---

## Task 9: 主界面重构（VhostsActivity）

**Files:**
- Modify: `app/src/main/res/layout/activity_vhosts.xml` (完全重写)
- Modify: `app/src/main/java/com/github/xfalcon/vhosts/VhostsActivity.java` (重构主逻辑)

**Interfaces:**
- Consumes: `HostListAdapter`、`HostEditActivity`、`HostsLoader.migrateIfNeeded`
- Produces: RecyclerView 列表 + 底部启停按钮 + 添加菜单

- [ ] **Step 1: 重写主界面布局**

`app/src/main/res/layout/activity_vhosts.xml` 完全替换为：

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:padding="16dp">

        <TextView
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="@string/host_profiles_title"
            android:textSize="20sp"
            android:textStyle="bold" />

        <ImageButton
            android:id="@+id/btn_add"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:background="?attr/selectableItemBackground"
            android:src="@drawable/fab_setting"
            android:contentDescription="@string/add_profile" />

        <ImageButton
            android:id="@+id/btn_settings"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="8dp"
            android:background="?attr/selectableItemBackground"
            android:src="@drawable/fab_setting"
            android:contentDescription="@string/action_settings" />
    </LinearLayout>

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/recycler_profiles"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />

    <TextView
        android:id="@+id/empty_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:gravity="center"
        android:text="@string/no_profiles"
        android:textSize="16sp"
        android:visibility="gone" />

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="horizontal"
        android:padding="16dp"
        android:gravity="center">

        <Button
            android:id="@+id/btn_launch"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:text="@string/launch"
            android:paddingTop="20dp"
            android:paddingBottom="20dp" />
    </LinearLayout>

    <com.github.clans.fab.FloatingActionMenu
        android:id="@+id/fab_menu"
        android:layout_width="wrap_content"
        android:layout_height="216dp"
        app:menu_labels_ellipsize="end"
        app:menu_labels_singleLine="true"
        app:menu_colorNormal="@color/primary"
        app:menu_colorPressed="@color/primary_dark"
        app:menu_colorRipple="@color/accent"
        android:layout_gravity="bottom|end"
        android:layout_margin="16dp">

        <com.github.clans.fab.FloatingActionButton
            android:id="@+id/fab_boot"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            app:fab_colorNormal="@color/fab_redNormal"
            app:fab_colorPressed="@color/fb_redPressed"
            android:src="@drawable/startup"
            app:fab_label="@string/fab_label_boot"/>

        <com.github.clans.fab.FloatingActionButton
            android:id="@+id/fab_donation"
            android:layout_width="wrap_content"
            android:layout_height="match_parent"
            android:src="@drawable/approve"
            app:fab_colorNormal="@color/fab_yellowNormal"
            app:fab_colorPressed="@color/fb_yellowPressed"
            app:fab_label="@string/fab_label_donation"/>
    </com.github.clans.fab.FloatingActionMenu>
</LinearLayout>
```

- [ ] **Step 2: 重构 VhostsActivity.java**

主要改动：
1. 移除旧的大开关和"重新选择"按钮逻辑
2. 加 RecyclerView + Adapter
3. 改底部按钮为"启动 / 停止"
4. 加迁移逻辑
5. 加添加菜单（新建 / 文件 / URL）

由于代码很长，我提供核心框架：

```java
package com.github.xfalcon.vhosts;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.github.clans.fab.FloatingActionMenu;
import com.github.xfalcon.vhosts.data.HostProfileRepository;
import com.github.xfalcon.vhosts.data.HostsLoader;
import com.github.xfalcon.vhosts.model.HostProfile;
import com.github.xfalcon.vhosts.ui.HostListAdapter;
import com.github.xfalcon.vhosts.util.HttpUtils;
import com.github.xfalcon.vhosts.util.LogUtils;
import com.github.xfalcon.vhosts.vservice.VhostsService;

import java.io.File;
import java.util.List;

public class VhostsActivity extends AppCompatActivity {
    private static final String TAG = VhostsActivity.class.getSimpleName();
    private static final int VPN_REQUEST_CODE = 0x0F;
    private static final int SELECT_FILE_CODE = 0x05;

    private RecyclerView recyclerView;
    private HostListAdapter adapter;
    private HostProfileRepository repo;
    private Button btnLaunch;
    private TextView emptyView;
    private ImageButton btnAdd, btnSettings;
    private FloatingActionMenu fabMenu;
    private ImageButton fabBoot, fabDonation;

    private androidx.localbroadcastmanager.content.BroadcastReceiver vpnStateReceiver = new androidx.localbroadcastmanager.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (VhostsService.BROADCAST_VPN_STATE.equals(intent.getAction())) {
                updateLaunchButton();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_vhosts);

        LogUtils.context = getApplicationContext();

        // 初始化仓储 & Adapter
        File profilesDir = new File(getFilesDir(), "profiles");
        repo = new HostProfileRepository(profilesDir);

        recyclerView = findViewById(R.id.recycler_profiles);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new HostListAdapter(this, profilesDir);
        adapter.setOnProfileClickListener(profile -> {
            Intent intent = new Intent(VhostsActivity.this, HostEditActivity.class);
            intent.putExtra(HostEditActivity.EXTRA_PROFILE_ID, profile.getId());
            startActivity(intent);
        });
        recyclerView.setAdapter(adapter);

        emptyView = findViewById(R.id.empty_view);
        btnLaunch = findViewById(R.id.btn_launch);
        btnAdd = findViewById(R.id.btn_add);
        btnSettings = findViewById(R.id.btn_settings);
        fabMenu = findViewById(R.id.fab_menu);
        fabBoot = findViewById(R.id.fab_boot);
        fabDonation = findViewById(R.id.fab_donation);

        // 一次性迁移旧数据
        migrateIfNeeded();

        // 更新列表显示
        refreshProfileList();

        // 启停按钮
        btnLaunch.setOnClickListener(v -> {
            if (VhostsService.isRunning()) {
                VhostsService.stopVService(VhostsActivity.this);
            } else {
                startVPN();
            }
        });

        // 添加方案
        btnAdd.setOnClickListener(v -> showAddMenu());

        // 设置
        btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(VhostsActivity.this, SettingsActivity.class));
        });

        // FAB: 开机自启
        fabBoot.setOnClickListener(v -> {
            if (BootReceiver.getEnabled(this)) {
                BootReceiver.setEnabled(this, false);
                fabBoot.setColorNormalResId(R.color.startup_off);
            } else {
                BootReceiver.setEnabled(this, true);
                fabBoot.setColorNormalResId(R.color.startup_on);
            }
        });
        if (BootReceiver.getEnabled(this)) {
            fabBoot.setColorNormalResId(R.color.startup_on);
        }

        // FAB: 捐赠
        fabDonation.setOnClickListener(v -> {
            startActivity(new Intent(VhostsActivity.this, DonationActivity.class));
        });

        // 广播监听 VPN 状态
        LocalBroadcastManager.getInstance(this).registerReceiver(vpnStateReceiver,
            new android.content.IntentFilter(VhostsService.BROADCAST_VPN_STATE));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshProfileList();
        updateLaunchButton();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(vpnStateReceiver);
    }

    private void refreshProfileList() {
        List<HostProfile> profiles = repo.findAll();
        adapter.setProfiles(profiles);
        emptyView.setVisibility(profiles.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
        recyclerView.setVisibility(profiles.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private void updateLaunchButton() {
        if (VhostsService.isRunning()) {
            btnLaunch.setText(R.string.stop);
        } else {
            btnLaunch.setText(R.string.launch);
        }
    }

    private void startVPN() {
        Intent vpnIntent = VhostsService.prepare(this);
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        } else {
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
        }
    }

    private void showAddMenu() {
        String[] options = {
            getString(R.string.add_new),
            getString(R.string.add_from_file),
            getString(R.string.add_from_url)
        };

        new AlertDialog.Builder(this)
            .setTitle(R.string.add_profile)
            .setItems(options, (dialog, which) -> {
                switch (which) {
                    case 0:
                        addNewProfile();
                        break;
                    case 1:
                        selectFileToImport();
                        break;
                    case 2:
                        addFromUrl();
                        break;
                }
            })
            .show();
    }

    private void addNewProfile() {
        final EditText input = new EditText(this);
        input.setHint(R.string.enter_title);

        new AlertDialog.Builder(this)
            .setTitle(R.string.add_new)
            .setView(input)
            .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> {
                String title = input.getText().toString().trim();
                if (!title.isEmpty()) {
                    try {
                        HostProfile p = repo.create(title, "NEW", null, "");
                        refreshProfileList();
                        // 进编辑页
                        Intent intent = new Intent(VhostsActivity.this, HostEditActivity.class);
                        intent.putExtra(HostEditActivity.EXTRA_PROFILE_ID, p.getId());
                        startActivity(intent);
                    } catch (Exception e) {
                        LogUtils.e(TAG, "Error creating profile", e);
                        Toast.makeText(VhostsActivity.this, "Error creating profile", Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton(R.string.dialog_cancel, null)
            .show();
    }

    private void selectFileToImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, SELECT_FILE_CODE);
    }

    private void addFromUrl() {
        final EditText input = new EditText(this);
        input.setHint(R.string.url_error);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);

        new AlertDialog.Builder(this)
            .setTitle(R.string.add_from_url)
            .setView(input)
            .setPositiveButton(R.string.dialog_confirm, (dialog, which) -> {
                String url = input.getText().toString().trim();
                if (!isValidUrl(url)) {
                    Toast.makeText(VhostsActivity.this, R.string.invalid_url, Toast.LENGTH_SHORT).show();
                    return;
                }
                downloadFromUrl(url);
            })
            .setNegativeButton(R.string.dialog_cancel, null)
            .show();
    }

    private void downloadFromUrl(final String url) {
        // 后台下载
        new Thread() {
            public void run() {
                try {
                    String content = HttpUtils.get(url);
                    HostProfile p = repo.create(Uri.parse(url).getLastPathSegment(), "URL", url, content);
                    runOnUiThread(() -> {
                        int records = countRecords(content);
                        Toast.makeText(VhostsActivity.this,
                            getString(R.string.records_count, records),
                            Toast.LENGTH_SHORT).show();
                        refreshProfileList();
                    });
                } catch (Exception e) {
                    LogUtils.e(TAG, "Download error", e);
                    runOnUiThread(() -> Toast.makeText(VhostsActivity.this, R.string.down_error, Toast.LENGTH_SHORT).show());
                }
            }
        }.start();
    }

    private boolean isValidUrl(String str) {
        String regex = "http(s)?://([\\w-]+\\.)+[\\w-]+(/[\\w- ./?%&=]*)?";
        return str.matches(regex);
    }

    private int countRecords(String content) {
        int count = 0;
        for (String line : content.split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                count++;
            }
        }
        return count;
    }

    private void migrateIfNeeded() {
        try {
            SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
            String hostUri = settings.getString("HOST_URI", null);
            boolean isNet = settings.getBoolean("IS_NET", false);

            String content = null;
            if (isNet) {
                // 读 net_hosts 内容
                try {
                    content = readFile(openFileInput("net_hosts"));
                } catch (Exception ignore) {}
            } else if (hostUri != null) {
                // 读 SAF URI 内容
                try {
                    content = readFile(getContentResolver().openInputStream(Uri.parse(hostUri)));
                } catch (Exception ignore) {}
            }

            File profilesDir = new File(getFilesDir(), "profiles");
            HostsLoader.migrateIfNeeded(repo, profilesDir, hostUri, isNet, content);
        } catch (Exception e) {
            LogUtils.e(TAG, "Migration error", e);
        }
    }

    private String readFile(java.io.InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(is));
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line).append("\n");
        }
        br.close();
        is.close();
        return sb.toString();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            // VPN 授权通过
            startService(new Intent(this, VhostsService.class).setAction(VhostsService.ACTION_CONNECT));
        } else if (requestCode == SELECT_FILE_CODE && resultCode == RESULT_OK && data != null) {
            // 文件导入
            Uri fileUri = data.getData();
            try {
                String content = readFile(getContentResolver().openInputStream(fileUri));
                String title = getFileName(fileUri);
                HostProfile p = repo.create(title, "FILE", null, content);
                int records = countRecords(content);
                Toast.makeText(this,
                    getString(R.string.records_count, records),
                    Toast.LENGTH_SHORT).show();
                refreshProfileList();
            } catch (Exception e) {
                LogUtils.e(TAG, "Import error", e);
                Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private String getFileName(Uri uri) {
        android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
        cursor.moveToFirst();
        String name = cursor.getString(nameIndex);
        cursor.close();
        return name.replaceAll("\\.[^.]*$", "");  // 去掉扩展名
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
sh gradlew assembleGithubDebug -x test 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/res/layout/activity_vhosts.xml \
        app/src/main/java/com/github/xfalcon/vhosts/VhostsActivity.java
git commit -m "feat: redesign VhostsActivity as profile list with launch button"
```

---

## Task 10: 移除设置页的 URL 下载项

**Files:**
- Modify: `app/src/main/res/xml/preferences.xml`
- Modify: `app/src/main/java/com/github/xfalcon/vhosts/SettingsFragment.java`

**Interfaces:**
- Consumes: 现有的自定义 DNS 设置项
- Produces: URL 下载能力移除，保留自定义 DNS

- [ ] **Step 1: 修改 preferences.xml**

打开 `app/src/main/res/xml/preferences.xml`，删除第一组（`pref_ps_set_hosts_url`），保留第二组（`pref_ps_set_cus_dns`）：

```xml
<?xml version="1.0" encoding="utf-8"?>
<PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android">
    <PreferenceCategory
            android:title="@string/pref_ps_set_cus_dns">
        <EditTextPreference
                android:defaultValue="8.8.8.8"
                android:selectAllOnFocus="true"
                android:singleLine="true"
                android:title="@string/pref_dns" android:key="IPV4_DNS"/>
        <CheckBoxPreference
                android:defaultValue="false"
                android:title="@string/pref_dns_title" android:key="IS_CUS_DNS"/>
    </PreferenceCategory>
</PreferenceScreen>
```

- [ ] **Step 2: 修改 SettingsFragment.java**

删除所有与 `HOSTS_URL`、`IS_NET`、URL 下载、`setProgressDialog`、`isUrl` 相关的代码。只保留 DNS 部分：

```java
package com.github.xfalcon.vhosts;

import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.preference.*;
import com.github.xfalcon.vhosts.util.LogUtils;
import org.xbill.DNS.Address;

public class SettingsFragment extends PreferenceFragmentCompat implements
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static String TAG = SettingsFragment.class.getName();

    public static final String PREFS_NAME = SettingsFragment.class.getName();
    public static final String IPV4_DNS = "IPV4_DNS";
    public static final String IS_CUS_DNS = "IS_CUS_DNS";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);
        final SharedPreferences sharedPreferences = getPreferenceScreen().getSharedPreferences();
        PreferenceScreen prefScreen = getPreferenceScreen();
        handleSummary(prefScreen, sharedPreferences);

        Preference dnsCustomPref = findPreference(IPV4_DNS);
        dnsCustomPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                String ipv4_dns = (String)newValue;
                try {
                    Address.getByAddress(ipv4_dns);
                    return true;
                } catch (Exception e) {
                    LogUtils.e(TAG, e.getMessage(), e);
                    android.widget.Toast.makeText(preference.getContext(), getString(R.string.dns4_error), android.widget.Toast.LENGTH_LONG).show();
                }
                return false;
            }
        });
    }

    private void handleSummary(PreferenceGroup preferenceGroup, SharedPreferences sharedPreferences) {
        int count = preferenceGroup.getPreferenceCount();
        for (int i = 0; i < count; i++) {
            Preference p = preferenceGroup.getPreference(i);
            if (p instanceof PreferenceCategory) {
                handleSummary((PreferenceCategory) p, sharedPreferences);
            }
            if (!(p instanceof CheckBoxPreference)) {
                String value = sharedPreferences.getString(p.getKey(), "");
                setPreferenceSummary(p, value);
            }
        }
    }

    private void setPreferenceSummary(Preference preference, String value) {
        if (preference instanceof ListPreference) {
            ListPreference listPreference = (ListPreference) preference;
            int prefIndex = listPreference.findIndexOfValue(value);
            if (prefIndex >= 0) {
                listPreference.setSummary(listPreference.getEntries()[prefIndex]);
            }
        } else if (preference instanceof EditTextPreference) {
            preference.setSummary(value);
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        Preference preference = findPreference(key);
        if (null != preference) {
            if (!(preference instanceof CheckBoxPreference)) {
                String value = sharedPreferences.getString(preference.getKey(), "");
                setPreferenceSummary(preference, value);
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getPreferenceScreen().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public android.view.View onCreateView(android.view.LayoutInflater inflater, android.view.ViewGroup container, Bundle savedInstanceState) {
        inflater.getContext().setTheme(R.style.AppPreferenceSettingsFragmentTheme);
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getPreferenceScreen().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(this);
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
sh gradlew assembleGithubDebug -x test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/res/xml/preferences.xml \
        app/src/main/java/com/github/xfalcon/vhosts/SettingsFragment.java
git commit -m "feat: remove URL download from settings, keep custom DNS"
```

---

## Task 11: 手动冒烟测试 & 集成验证

**Files:**
- None (测试步骤)

**Interfaces:**
- Consumes: 完整构建的 APK (`assembleGithubDebug`)
- Produces: 验证列表显示、编辑、启停等基本功能正常

- [ ] **Step 1: 构建最终 APK**

```bash
sh gradlew assembleGithubDebug 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

APK 路径：`app/build/outputs/apk/github/debug/app-github-debug.apk`

- [ ] **Step 2: 安装到设备/模拟器（若可用）**

```bash
sh gradlew installGithubDebug 2>&1 | tail -10
```

或手动安装：使用 Android Studio 或 `adb install` 命令。

- [ ] **Step 3: 手动验证（冒烟测试清单）**

1. 打开应用 → 应看到「暂无方案」提示
2. 点「+」添加 → 选「新建空白」→ 输入「Test」→ 进编辑页
3. 编辑页输入 `127.0.0.1 test.example.com` → 点「保存」 → 返回主界面
4. 列表显示「Test」一项，右侧开关为启用状态
5. 点「启动」→ 系统权限对话框 → 授权 → VPN 启动，按钮变「停止」
6. 再点列表「Test」→ 进编辑页，修改内容 → 保存 → 列表更新
7. 点「停止」→ VPN 停止，按钮变「启动」
8. 点「+」添加 → 选「新建空白」 → 「Hosts2」→ 启用两个方案 → 启动（应合并两个方案）
9. 进「设置」→ 只有「自定义 DNS」设置，无「远程 hosts URL」（验证移除成功）

- [ ] **Step 4: 验证编译无警告**

```bash
sh gradlew assembleGithubDebug 2>&1 | grep -i warning
```

应无与新代码相关的警告。

---

## Task 12: 最终提交 & 分支清理

**Files:**
- None (git 操作)

**Interfaces:**
- Produces: `feat/hosts-profiles-list` 分支完成，准备 PR 或合并

- [ ] **Step 1: 检查工作树状态**

```bash
git status
```

Expected: 只有已提交的改动，无未追踪文件（除 `.gradle` 等构建临时目录）

- [ ] **Step 2: 查看提交历史**

```bash
git log --oneline feat/hosts-profiles-list ^main | head -20
```

Expected: 12+ 个清晰的提交，每个提交对应一个任务

- [ ] **Step 3: 编译最终验证**

```bash
sh gradlew clean assembleGithubDebug 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交分支摘要**

```bash
git log --oneline main..feat/hosts-profiles-list | wc -l
```

验证提交数量。

完成！准备进行 Code Review 或向 `main` 提 PR。

---

## 总结

这个计划分为 12 个任务：

1. **环境准备** (1) — gradlew 权限修复
2. **纯逻辑 TDD** (5) — HostProfile、HostProfileRepository、DnsChange 改造、重载、迁移
3. **字符串资源** (1) — 新增多语言文案
4. **UI 层** (4) — RecyclerView Adapter、列表布局、编辑页、主界面重构、设置页修改
5. **集成测试** (1) — 冒烟测试 & 最终验证

每个任务 TDD 先行（测试失败 → 实现 → 测试通过 → 提交），UI 层则以编译通过 + 手动验证为准。所有改动逐步提交，保留完整历史记录。
