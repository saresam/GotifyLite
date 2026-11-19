# GotifyLite · Gotify 轻推送

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84.svg)](https://developer.android.com)
[![Size](https://img.shields.io/badge/APK-~220%20KB-brightgreen.svg)](#)

一个为**省电**而重写的 [Gotify](https://github.com/gotify/server) Android 客户端。

原版 [gotify/android](https://github.com/gotify/android) 功能齐全但后台耗电明显。本项目只保留"收消息、看消息"这一条主链路，把所有会持续唤醒 CPU/射频的设计全部去掉，最终得到一个 APK 约 220 KB、空闲时几乎不耗电的客户端。

兼容 gotify/server **v2.x / v3.x**（已在 v3.0.0 实测）。

<p align="center">
  <img src="docs/home-v20.webp" width="300" alt="首页" />
</p>

---

## 特性

- **WebSocket 长连接** `/stream`，消息到达零延迟
- **断线自动补收**：重连后按消息 id 拉取断线期间错过的消息
- **按优先级分流通知**：Gotify 优先级 0–10 映射到 4 个通知通道，可分别设置声音/震动/静音
- **消息历史**：本地 SQLite 缓存最近 500 条，断网也能翻看；按服务器地址分库，换服务器不会串消息
- **分页浏览**：每页 30 条，可上一页/下一页，也可点页码直接跳转（未加载的页会自动补拉）
- **应用筛选**：只显示某个应用的消息
- **支持 extras**：
  - `client::notification.click.url` — 点通知打开指定 URL
  - `android::action.onReceive.intentUrl` — 收到消息立即打开指定 URL
- **开机自启**（仅在用户主动开启过推送时）、网络恢复即重连、JobScheduler 兜底拉起
- **纯文本渲染**：不加载图片、不渲染 Markdown，这是有意的省电取舍

## 为什么比原版省电

原版耗电的主要原因，本项目全部规避：

| 原版 gotify/android | 本项目 |
|---|---|
| `AlarmManager.setExact(RTC_WAKEUP)` 精确闹钟定时重连，每次唤醒 CPU/射频 | 内存中的 Handler 指数退避重连（1s→60s，带 ±20% 抖动），**不使用任何闹钟** |
| OkHttp `pingInterval(1min)`，客户端每分钟额外发包 | 不主动 ping，只被动响应服务端每 45s 的 ping（OkHttp 自动回 pong） |
| Retrofit + Swagger 生成客户端 + Coil + Markwon + tinylog 等重型依赖 | **唯一第三方依赖 OkHttp**；无 androidx、无 GMS / play-services |
| 前台服务用 `specialUse` 类型 | Android 15 使用 `remoteMessaging`（无时长限制），低版本回退 `dataSync` |
| 连不上就高频重试 | 指数退避 + 抖动；令牌失效（401/403）时停止重试，等用户修正 |
| 持有 WakeLock / WifiLock | 不持有任何唤醒锁 |

结果：空闲时 CPU 可深度睡眠，只在收到消息或网络变化时短暂活动。

## 工作原理

**接收链路**

1. `PushService` 以 `GET /stream?token=<客户端令牌>` 建立 WebSocket 长连接
2. 收到帧（裸 Message JSON）→ 写入 `MessageStore` → `Notifier` 按优先级发通知 → 首页实时刷新
3. 断线后用内存中的指数退避重连；重连成功再调 `GET /message?limit=&since=lastId` 把错过的消息补收回来
4. 服务端每 45 秒发一次 ping，OkHttp 自动回 pong；客户端**不主动发包**

**协议要点**

- 认证头：`X-Gotify-Key: <客户端令牌>`
- 使用的 REST 接口：`GET /version`、`GET /application`、`GET /message?limit=&since=`、
  `GET /application/{id}/message`、`DELETE /message/{id}`
- WebSocket 帧内容是**裸 Message JSON**（`{id, appid, message, title, priority, date, extras}`），无信封
- 优先级分档：`≤0` 静音 / `1–3` 低 / `4–7` 普通 / `≥8` 紧急

## 构建

**环境要求**

- JDK 17
- Android SDK Platform 35 + Build-Tools 35.0.0
- Gradle 8.9（仓库自带 Wrapper，首次运行会联网下载）

```bash
git clone https://github.com/<your-name>/GotifyLite.git
cd GotifyLite

# Linux / macOS
./gradlew assembleRelease

# Windows
gradlew.bat assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

运行单元测试：

```bash
./gradlew testReleaseUnitTest
```

> 如果 `local.properties` 不存在，Gradle 会读取环境变量 `ANDROID_HOME` / `ANDROID_SDK_ROOT`；
> 也可以手动创建 `local.properties` 并写入 `sdk.dir=/path/to/android-sdk`。

### 签名

签名信息从**不入库**的 `keystore.properties` 读取：

```bash
cp keystore.properties.example keystore.properties
```

```properties
storeFile=keystore.jks
storePassword=你的 store 密码
keyAlias=你的 key 别名
keyPassword=你的 key 密码
```

- 没有 `keystore.properties` 时，`assembleRelease` 仍会成功，但产出的是 `app-release-unsigned.apk`
- **切勿提交** `keystore.jks`、`*.jks`、`keystore.properties`（已在 `.gitignore` 中排除）

## 安装与首次配置

1. 安装 APK（需允许"未知来源"）
2. 打开应用 → 右上角菜单 → **服务器设置**
3. 填写**服务器地址**（如 `https://push.example.com`）和**客户端令牌**
   - 令牌在 Gotify 网页端「客户端」里创建，**不是**「应用」的令牌
4. 点「保存并连接」，再点「测试连接」确认地址与令牌正确

## 系统保活设置（重要）

Android 会主动冻结后台应用，推送类应用必须手动放行。以 HyperOS / MIUI 为例：

1. **通知权限**：允许
2. **省电策略 → 无限制**：设置 → 应用 → Gotify 轻推送 → 省电策略 → 无限制
3. **自启动**：开启
4. **锁定最近任务**：多任务界面下拉本应用卡片，点锁形图标
5. 不要用「一键清理」清理本应用

其他厂商（EMUI / ColorOS / OriginOS 等）思路相同：允许自启动 + 后台不受限 + 锁定后台。

## 项目结构

```
GotifyLite/
├── app/src/main/java/com/gotify/lite/
│   ├── App.kt                      # Application，创建通知通道
│   ├── Prefs.kt                    # SharedPreferences 封装
│   ├── GotifyMessage.kt            # 消息模型与 JSON 解析
│   ├── GotifyApi.kt                # REST + WebSocket URL 构造
│   ├── StreamClient.kt             # WebSocket 封装（无客户端 ping）
│   ├── PushService.kt              # 前台服务：连接 / 重连 / 补收 / 通知
│   ├── Notifier.kt                 # 通知通道、优先级配色
│   ├── MessageStore.kt             # SQLite 历史（按服务器分库，上限 500）
│   ├── MainActivity.kt             # 首页：列表 / 分页 / 应用筛选
│   ├── MessageDetailActivity.kt    # 详情页：选择复制 / 删除 / 打开链接
│   ├── ServerSettingsActivity.kt   # 服务器地址与令牌
│   ├── BatterySettingsActivity.kt  # 省电设置引导
│   ├── BootReceiver.kt             # 开机自启
│   └── ReconnectJobService.kt      # JobScheduler 兜底
├── app/src/test/java/com/gotify/lite/   # 单元测试（9 个）
├── app/src/main/res/                    # 布局 / drawable / animator / 菜单
├── docs/                                # README 截图
├── keystore.properties.example          # 签名配置模板
└── gradle/wrapper/                      # Gradle Wrapper
```

## 技术栈

| 项 | 版本 |
|---|---|
| 语言 | Kotlin 2.0.21 |
| Gradle / AGP | 8.9 / 8.7.3 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| 第三方依赖 | OkHttp 4.12.0（唯一） |
| 构建优化 | R8 + resource shrink |


## 常见问题

**状态显示"令牌无效"**
令牌被删除或过期。去网页端「客户端」重新创建，在应用内重新保存。

**收不到消息**
1. 先看应用内状态是否为「已连接」
2. 已连接但没通知 → 检查通知权限、对应优先级通道是否被关闭（每个优先级是独立通道）
3. 显示已连接但断线后不恢复 → 检查省电策略是否「无限制」、自启动是否开启

**服务器是 http（无证书）**
应用允许明文流量，局域网 IP 可直接使用。公网部署建议配置 HTTPS。

**为什么不显示 Markdown / 图片**
为省电与体积只渲染纯文本。需要富文本请使用官方客户端。

## 致谢

- [gotify/server](https://github.com/gotify/server) — 服务端与协议
- [gotify/android](https://github.com/gotify/android) — 行为参考

## 许可

[MIT License](LICENSE) © 2026 saresam

任何人可自由使用、修改、分发本项目（包括商业用途），只需保留版权声明与许可声明。
