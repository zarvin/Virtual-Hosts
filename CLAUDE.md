# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

Virtual Hosts（`com.github.xfalcon.vhosts`）是一个 Android 应用,让开发者在**免 root** 的设备上自定义 hosts 解析。它不修改 `/system/etc/hosts`,而是启动一个本地 `VpnService`,拦截 DNS 流量并按用户提供的 hosts 文件在本地作答。支持**通配符 DNS 记录**(如 `127.0.0.1 .a.com` 匹配所有子域名)。

## 构建与测试命令

项目有两个 product flavor(维度 `CHANNEL`):`github`(F-Droid / GitHub 发布版)和 `googleplay`(Play 商店版,仅 `BuildConfig.IS_GooglePlay` 不同)。Gradle 任务名遵循 `<task><Flavor><BuildType>` 格式,因此**必须带 flavor**:

```bash
./gradlew assembleGithubDebug            # 构建 github 渠道 debug APK
./gradlew installGithubDebug             # 构建并安装到已连接设备
./gradlew assembleDebug                  # 构建全部 flavor 的 debug 变体

./gradlew testGithubDebugUnitTest        # 运行 JVM 单元测试(app/src/test)
./gradlew testGithubDebugUnitTest --tests "com.github.xfalcon.vhosts.ExampleUnitTest"   # 单个测试类
./gradlew connectedGithubDebugAndroidTest # 仪器测试(app/src/androidTest,需设备/模拟器)

./gradlew lintGithubDebug                # Android Lint
./gradlew clean
```

- 工具链:AGP 8.12.3,Gradle wrapper 8.13,Java 8(source/target 1.8),`compileSdk 36` / `minSdk 19` / `targetSdk 36`。
- **Firebase**:`app/build.gradle` 应用了 `google-services` 插件,`app/google-services.json` 已入库,直接可构建;若接入其他 Firebase 项目需替换该文件。
- 测试覆盖极少(仅 `ExampleUnitTest` 与 `PressureTest` 两个占位测试),没有 CI 强制的覆盖率门槛。

## 核心架构(数据包管线)

本应用基于 **LocalVPN**(Mohamed Naufal / hexene,APL 2.0)改造而来 —— 参见 `vservice/` 中带 Apache 头的文件。核心思路:建立一个用户态 TUN 接口,读取原始 IP 包;对 DNS 查询(UDP 53 端口)在本地合成应答,其余流量通过 `protect()` 过的 socket 透传。理解全貌需要串联以下几个文件:

**启动握手** —— `VhostsActivity`(唯一 UI,一个开关按钮):
1. 用户经 SAF `ACTION_OPEN_DOCUMENT` 选择本地 hosts 文件(持久化 URI 存入 preference `HOST_URI`),或在设置页从 URL 下载到内部文件 `net_hosts`(此时 `IS_NET=true`)。
2. 打开开关 → `VpnService.prepare()` 弹系统授权 → `onActivityResult` → 以 `ACTION_CONNECT` 启动 `VhostsService`。

**VPN 服务** —— `vservice/VhostsService`(`extends VpnService`),`onCreate` 中:
- `setupHostFile()`:读取所选 hosts 流,后台线程交给 `DnsChange.handle_hosts()` 解析。
- `setupVPN()`:构建 TUN,分配 VPN 地址(IPv4 `192.0.2.111/32` 等)。**关键**:只对 DNS 服务器地址 `addRoute(...,32/128)`,全量捕获路由 `VPN_ROUTE "0.0.0.0"` 是被注释掉的 —— 所以**只有 DNS 流量进入隧道**,这也是它省电、不干扰其他连接的原因。同时 `addDisallowedApplication` 放行了一批 Google 应用。
- 起一个固定 5 线程池,提交 `UDPInput`、`UDPOutput`、`TCPInput`、`TCPOutput` 和 `VPNRunnable`。

**中央泵** —— `VhostsService.VPNRunnable`:循环从 TUN fd 读 IP 包 → 用 `Packet` 解析 → UDP 投入 `deviceToNetworkUDPQueue`、TCP 投入 `deviceToNetworkTCPQueue`;同时把 `networkToDeviceQueue` 里的包写回 TUN。缓冲区由 `ByteBufferPool` 复用,空闲时 `sleep(11ms)` 轮询。三条 `ConcurrentLinkedQueue` 是各线程间的唯一纽带。

**DNS 拦截(应用的核心)** —— `UDPOutput.run()` 检查每个出站 UDP 包:若 `destinationPort == 53`,调用 `DnsChange.handle_dns_packet()`:
- 用 dnsjava(`org.xbill.DNS.Message`)解析查询,按类型在 `DOMAINS_IP_MAPS4`(A)或 `DOMAINS_IP_MAPS6`(AAAA)查表。
- **通配符匹配**:精确命中失败时,逐段剥掉最左标签做后缀匹配(`a.com.` → `.com.` 形式)。
- 命中:合成应答记录、置 QR 标志、交换源/目的、原地改写 UDP 包,直接 `offer` 到 `networkToDeviceQueue`(**本地作答,不出设备**)。
- 未命中(返回 `null`):经 `protect()` 过的 `DatagramChannel`(LRU 缓存 50 个)转发给真实 DNS 服务器。

**TCP/UDP 透传**:`UDPInput`/`TCPInput` 用 NIO `Selector` 读回响应;`TCB`(Transmission Control Block)维护 TCP 连接状态机。这部分沿用自 LocalVPN;由于路由只覆盖 DNS,实际流经隧道的主要是 DNS。

**hosts 文件格式**:`DnsChange.handle_hosts()` 正则解析 `IP 域名` 行(`#` 为注释)。键以 FQDN 末尾点存储;含 `:` 判为 IPv6 入 MAPS6,否则入 MAPS4。

## 其他组件与约定

- **入口/组件**:`BootReceiver`(开机自启)、`QuickStartTileService`(快捷设置磁贴)、`QuickStartWidget`(桌面小部件)、`DonationActivity` + Play Billing(捐赠)、`SettingsActivity`/`SettingsFragment`(自定义 DNS、URL 下载 hosts)。`NetworkReceiver` 当前整体被注释停用。
- **Preference 键**(定义在 `SettingsFragment`):`IS_NET`(用下载的 net hosts 还是本地文件)、`HOST_URI`、`HOSTS_URL`、`net_hosts`、`IPV4_DNS` + `IS_CUS_DNS`(自定义 DNS)。
- **内置 dnsjava**:`app/src/main/java/org/xbill/DNS/` 是**源码内置**的 dnsjava,不是 Gradle 依赖。改动这里等于打本地补丁,升级时勿与项目自有代码混淆。
- **依赖混用**:同时用了旧版 `com.android.support:*:33.0.0` 与 AndroidX(靠 `android.enableJetifier=true` 桥接)。升级支持库时需谨慎,二者边界脆弱。
- **兼容底线**:`minSdk 19`,代码中大量 `Build.VERSION.SDK_INT` 分支,新增 API 调用务必做版本判断。
- **`hosts_to_wildcard.py`**:独立的 Python 2 离线工具,把大型扁平 hosts 文件按 IP 归并压缩成通配符形式(逻辑与 `DnsChange` 的后缀匹配对应)。不参与 App 构建。

## 许可证

App 主体为 GPL-3.0(`Copyright 2017 xfalcon`);`vservice/` 中源自 LocalVPN 的文件为 Apache-2.0。改动时保留对应文件头。
