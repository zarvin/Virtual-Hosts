# Hosts 方案列表（多方案管理）设计

日期：2026-07-23
分支：`feat/hosts-profiles-list`
状态：已确认，待写实现计划

## 1. 背景与目标

当前主界面只能选中**一个** hosts 文件后开启 VPN。每次切换都要重新去系统文件选择器里挑，很麻烦。

目标：改造成类似 SwitchHosts 的体验——

- 主界面是一个 **hosts 方案列表**，每一项右侧有一个开关，决定哪些方案生效（可同时启用多个）。
- 底部一个**固定按钮「启动 / 停止」**控制 VPN。
- 点击列表项进入编辑页，可**查看、编辑、保存** hosts 内容。

## 2. 现状（改造前）

- 数据：单个外部文件的 SAF URI 存在 preference `HOST_URI`；或从 URL 下载到内部文件 `net_hosts`，用 `IS_NET` 标记走哪条路径。
- 加载：`VhostsService.setupHostFile()` 读**一个**流 → `DnsChange.handle_hosts(InputStream)` 解析。该方法每次 `new ConcurrentHashMap`，即**每次只能装一个文件**。
- 解析结果：`DnsChange.DOMAINS_IP_MAPS4`（A 记录）/ `DOMAINS_IP_MAPS6`（AAAA），运行时由 `UDPOutput` 命中 53 端口时查表本地作答。
- 主界面：`activity_vhosts.xml` = 一个旋转 90° 的大 `SwitchButton` + 「重新选择 hosts」按钮 + 一个 FloatingActionMenu（设置 / 开机自启 / 捐赠）。
- 设置页：`preferences.xml` 有「远程 hosts URL 下载」（`HOSTS_URL` + `IS_NET`）与「自定义 DNS」（`IPV4_DNS` + `IS_CUS_DNS`）两组。

## 3. 已确认的关键决策

| 决策点 | 结论 |
| --- | --- |
| 方案来源 | 三种添加方式：应用内**新建空白**、**从文件导入内容**、**从 URL 下载内容**。导入即把内容**复制**进应用内、可离线编辑。 |
| 运行时改动生效 | **自动即时重载**：运行中切换开关或保存编辑，自动重建解析表，立即生效，隧道不断。 |
| 存储实现 | **方案 A**：轻量元数据索引 + 每条内容独立文件。不引入新依赖。 |
| 冲突规则 | **靠前优先**：同域名不同 IP 时，列表越靠上的方案赢（解析改用 `putIfAbsent`），符合传统 `/etc/hosts`「第一条生效」直觉。 |
| 排序 | 先用**添加顺序**，保留 `order` 字段，暂不做拖拽排序。 |
| 迁移 | 首次运行新版，把旧的 `HOST_URI` / `net_hosts` 内容自动转成第一个已启用方案。 |
| 设置页 | 移除 URL 下载入口（整合进「添加方案」），**保留自定义 DNS**。 |

## 4. 数据模型

不可变值对象（遵循项目不可变风格：`with*` / 更新返回新副本，不就地改）：

```
HostProfile {
  id        : String            // UUID，同时用作内容文件名
  title     : String            // 显示名，新建/导入时命名，可改
  enabled   : boolean           // 右侧开关
  order     : int               // 顺序 = 合并优先级（越小越靠前、优先级越高）
  sourceType: NEW | FILE | URL  // 来源标记
  sourceRef : String            // URL 来源时记下地址（便于将来手动刷新）；其他为空
}
```

内容不放在模型里，单独存文件（见下）。

## 5. 存储实现（方案 A）

- **索引**：独立文件 `filesDir/profiles/index.json`，存一段 JSON（用 Android 内置 `org.json.JSONArray/JSONObject` 手写序列化，**无需引入 Gson/Moshi**），记录每个方案的元数据（不含内容）。
- **内容**：每个方案的 hosts 文本存 `filesDir/profiles/<id>.hosts`。hosts 内容可能很大（URL 订阅上万行），独立文件便于流式读取与整文件覆写。

### `HostProfileRepository`（仓储模式）

对 UI 与服务屏蔽存储细节。为便于 JVM 单元测试，构造时接收一个 `File baseDir`（Android 侧传 `context.getFilesDir()`），不直接依赖 `Context`。

```
List<HostProfile> findAll()                 // 读索引，按 order 升序
List<HostProfile> findEnabled()             // 仅启用，按 order 升序
HostProfile       findById(String id)
String            readContent(String id)
HostProfile       create(title, sourceType, sourceRef, content)  // 新方案默认 enabled=true、order 追加末尾；写内容文件 + 追加索引，返回新对象
HostProfile       updateMeta(HostProfile p) // 更新 title/enabled/order
void              updateContent(String id, String content)       // 覆写内容文件
void              delete(String id)          // 删内容文件 + 从索引移除
```

所有写操作显式处理 IO 异常并向上层返回结果，绝不静默吞掉。

## 6. 主界面（重构 `VhostsActivity` + `activity_vhosts.xml`）

```
┌──────────────────────────────┐
│ Virtual Hosts          ⚙  +  │   顶部：设置、添加（新建/文件/URL）
├──────────────────────────────┤
│  导入的 hosts            [●]  │   RecyclerView：标题 + 启用开关
│  广告屏蔽                [○]  │   点条目 → 编辑页
│  测试环境                [●]  │   长按 → 重命名 / 删除
│  …                            │
├──────────────────────────────┤
│         [   启 动   ]         │   底部固定按钮
└──────────────────────────────┘
```

- `RecyclerView` + `HostListAdapter`（数据来自 `HostProfileRepository.findAll()`）。
- 列表项 `item_host_profile.xml`：标题 + `SwitchButton`（或原生 `Switch`）。切换开关 → `updateMeta(enabled)`；若 VPN 运行中 → 触发重载。
- **添加**：顶部 `+` 弹三选一：
  - 新建空白 → 弹标题输入 → `create` 空方案（默认启用）→ 进编辑页。
  - 从文件 → 复用现有 SAF `ACTION_OPEN_DOCUMENT`，**读取内容**存为新方案（不再只存 URI）。
  - 从 URL → 输入 URL → 后台 `HttpUtils.get()` 下载 → 存为新方案。复用现有 URL 校验与下载逻辑。
- **底部按钮**：未运行显示「启动」，运行中显示「停止」。启停复用现有 `VhostsService.startVService/stopVService` + `VpnService.prepare()` 授权流程（即现有 `startVPN()` / `onActivityResult` 那套）。
- 现有开机自启、捐赠等入口保留（可继续放在 FAB 或菜单）。

## 7. 编辑页（新 `HostEditActivity` + `activity_host_edit.xml`）

```
┌──────────────────────────────┐
│ ←  [标题输入框]        保存   │
├──────────────────────────────┤
│ 127.0.0.1  a.com              │   大文本 EditText，等宽字体
│ 127.0.0.1  .b.com   # 通配符   │
│ …                             │
└──────────────────────────────┘
```

- 传入 `profileId`：新建时由主界面先 `create` 出空方案（默认启用）再进入；编辑页只处理已存在的方案，逻辑简单。
- 保存：`updateContent(id, text)` +（如改名）`updateMeta`。保存后若 VPN 运行且该方案已启用 → 触发重载。
- 基础校验：空内容提示；可选地复用 `DnsChange` 解析统计有效记录数并 Toast 反馈（沿用现有 `down_success` 文案风格）。

## 8. 合并与生效（`DnsChange` 改造）

当前 `handle_hosts` 每次重置单张表且「后写覆盖」。改造为支持多方案合并、靠前优先、可运行时重载：

- 新增 `loadProfiles(List<String> contentsInOrder)`：
  - 构建两张**局部**新表 `map4` / `map6`。
  - 按传入顺序（= 已启用方案的 `order` 升序）逐条解析记录，用 **`putIfAbsent`** 写入 → 先到先得 = **靠前优先**。同一方案内也是先到先得。
  - 全部构建完，**原子替换**静态引用 `DOMAINS_IP_MAPS4/6`。
- 并发可见性：`DOMAINS_IP_MAPS4/6` 改为 **`volatile`**。VPN 的 `UDPOutput` 线程读取时，始终看到「完整的旧表」或「完整的新表」，不会读到半更新状态。
- 保留原 `handle_hosts(InputStream)` 供内部复用（如逐方案统计记录数），但主加载路径走 `loadProfiles`。

### 运行时重载入口

- 主界面/编辑页在改动后，若 `VhostsService.isRunning()`，调用一个统一入口重建表：
  - 读 `repository.findEnabled()` → 依次 `readContent` → `DnsChange.loadProfiles(contents)`。
  - 在后台线程执行（IO + 解析），完成后原子替换。**无需重启 VPN 服务、隧道不断**。
- 入口可实现为 `DnsChange`/一个 `HostsLoader` 的静态方法，或经 `VhostsService` 的 `ACTION_RELOAD` intent 转发。倾向直接静态调用（map 本身是静态的），更简单。

## 9. 服务与启动路径（`VhostsService`）

- `setupHostFile()` 改为：`repository.findEnabled()` → 合并内容 → `DnsChange.loadProfiles(...)`（后台线程，保持现有的空结果 Toast 提示）。
- 无启用方案时给出明确提示（复用 `no_local_record` 一类文案），不静默启动一个空表。

## 10. 迁移与兼容

- **迁移**：在主界面启动时检查一次性标志 `PROFILES_MIGRATED`。若为假：
  - 若旧 `HOST_URI` 可读，读取其内容 → `create("导入的 hosts", FILE, null, content)`（默认启用）。
  - 否则若 `net_hosts` 存在，读取 → `create("导入的 hosts", URL, HOSTS_URL, content)`（默认启用）。
  - 置 `PROFILES_MIGRATED = true`，避免重复迁移。旧 key 保留无害。
- **开机自启 / 快捷磁贴 / 桌面 widget**：「启动」语义不变，仍调 `VhostsService.startVService`，只是数据源已变成「所有已启用方案」——这些组件基本无需改动。
- **设置页**：从 `preferences.xml` 移除第一组（`HOSTS_URL` 的 `EditTextPreference` + `IS_NET` 的 `CheckBoxPreference`）；`SettingsFragment` 移除对应的 URL 校验 / 下载对话框逻辑（`urlCustomPref` 监听、`setProgressDialog`、`isUrl`），这些能力迁移到「添加方案 - 从 URL」里复用。**保留自定义 DNS 部分**。

## 11. 涉及文件

**新增**
- `vhosts/model/HostProfile.java` —— 不可变值对象
- `vhosts/data/HostProfileRepository.java` —— 仓储（索引 JSON + 内容文件）
- `vhosts/HostEditActivity.java` —— 编辑页
- `vhosts/HostListAdapter.java` —— RecyclerView adapter
- `vhosts/AddProfileHelper.java`（可选）—— 封装「新建 / 从文件 / 从 URL」添加流程
- `res/layout/activity_host_edit.xml`
- `res/layout/item_host_profile.xml`

**修改**
- `VhostsActivity.java` —— 主界面重构为列表 + 底部启停 + 添加菜单 + 触发迁移/重载
- `res/layout/activity_vhosts.xml` —— RecyclerView + 底部固定按钮
- `vservice/DnsChange.java` —— `loadProfiles`、`putIfAbsent`、`volatile`、重载入口
- `vservice/VhostsService.java` —— `setupHostFile` 从仓储加载全部已启用方案
- `SettingsFragment.java` + `res/xml/preferences.xml` —— 移除 URL 下载项
- `util/FileUtils.java` —— 补充 `readFile` 辅助（现只有 `writeFile`）
- `res/values/strings.xml`（及各语言）—— 新文案（列表空态、添加菜单、编辑页、启动/停止等）
- `app/build.gradle` —— 如主界面用到 `RecyclerView`，确认依赖（`recyclerview` / 现有 support 库已含）

## 12. 测试计划

- **`DnsChangeTest`（JVM 单元测试，`app/src/test`）**
  - 单方案解析：`IP domain` 行、`#` 注释、通配符 `.a.com` 后缀匹配。
  - 多方案合并 + **靠前优先**：两方案对同一域名给不同 IP，断言靠前的赢。
  - IPv4/IPv6 按含 `:` 分流到 `MAPS4`/`MAPS6`。
  - （`handle_hosts` 仅依赖 `InputStream` 与纯 Java 的 `org.xbill.DNS.Address`，可在 JVM 直接跑。）
- **`HostProfileRepositoryTest`（JVM，用临时目录）**
  - 因仓储接收 `File baseDir`，可用 JUnit `TempDir` 测试 create/read/update/delete、启用过滤、按 order 排序、索引与内容文件一致性。
- **迁移**：验证旧 `HOST_URI` / `net_hosts` 存在时生成首个启用方案，且 `PROFILES_MIGRATED` 防重复。
- 目标：新增核心逻辑（合并/优先级/仓储）覆盖到位；UI 层以手动验证为主。

## 13. 非目标（YAGNI）

- 拖拽排序 UI（保留 `order` 字段，将来再加）。
- 远程 URL 的自动/定时刷新（当前仅添加时下载一次；`sourceRef` 已记下，便于将来加「手动刷新」）。
- 方案分组、文件夹。
- 云同步 / 导入导出全部方案。
```
