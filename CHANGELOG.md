# 更新日志

本文件由 `assembleRelease` 强制校验：`versionName` 必须在这里有对应小节，否则构建失败。
升版本请用 `tools/bump-version.sh`，它会同时改 `versionCode` / `versionName` 并插入新小节。

## 3.8

- 修复升版本脚本与构建门禁不一致：桌面版 `bump-version.sh` 只改版本号、不写 CHANGELOG，
  会导致 `verifyVersion` 直接失败；现统一为无参数自动 +1，并同步插入 CHANGELOG 小节
- 保持 versionCode 每次 +1（28 → 29），避免覆盖安装时分不清先后

## 3.7

基于外部改进版本合并，包含 3.5–3.6 的全部改动；核心是一次安全性与健壮性加固。

**安全**

- 客户端令牌改用 **Android Keystore + AES-256/GCM** 加密后落盘（`SecretStore` / `SecretBox`），
  SharedPreferences 里只留密文；旧版明文令牌在读取时自动就地升级为密文
- 新增 `SafeUrl`：消息 `extras` 里的 `click.url` / `intentUrl` 只放行 http/https，
  拦截 `javascript:`、`file:`、`content:`、`intent:` 等任意 scheme（这些值等同外部输入）
- 新增 `TransportSecurity`：使用明文 HTTP 时在设置页与状态栏明确提示“令牌与消息不加密传输”

**稳定性与正确性**

- 新增 `CatchUpPolicy`：断线补收的纯决策逻辑。首次连接只静默确立基准，不会把服务器历史当新消息推送；
  服务端被重建（id 倒退）时自动重置基准，避免后续消息被永久判为已读
- 本地消息库命名改为 **归一化地址 + SHA-256 前 8 字节**（`StoreDbName`），修掉 32 位 `hashCode`
  的碰撞风险，并让 `https://a.example` 与 `https://a.example/` 归一到同一个库
- 新增 `NotifPermission`：Android 13+ 的 `POST_NOTIFICATIONS` 运行时权限首次进入主动申请，
  拒绝后不再反复弹框；未授权时状态栏给出可点击提示

**界面**

- 服务器设置、省电设置、外观模式合并为单一「设置」页（四张卡片：连接 / 外观 / 省电与保活 / 关于）
- 新增**深色模式**：跟随系统 / 浅色 / 深色三选一，即时生效；配套 `values-night` 颜色与主题
- 新增 `Palette`：优先级分档配色改为资源引用，深色模式下色条与胶囊自动切换

**工程**

- 新增 `verifyVersion` 任务：`assembleRelease` 前校验 CHANGELOG 已登记当前版本
- 新增 `tools/bump-version.sh` 一键升版本
- 移除已被设置页取代的 `ServerSettingsActivity` / `BatterySettingsActivity` 及其布局

## 3.4

- 发布准备：签名配置外置到 `keystore.properties`、示例地址去个人化、仓库结构与文档整理
- 新增 MIT 许可证

## 3.3

- 状态条上下内边距收紧（9dp → 5dp），应用筛选胶囊 36dp → 30dp
- 上一页 / 下一页 / 页码加入轻微按压缩放动画
- 点击页码弹出页面列表，可直接跳转；未加载的页按游标自动补拉

## 3.2

- 详情页标题、应用名/时间支持长按选择复制

## 3.1

- 修复应用筛选胶囊文字与箭头被裁切
- 移除下拉刷新手势（保留菜单「刷新消息」）

## 3.0

- 状态条「已连接 · 刚刚」合并为同一行
- 应用筛选下拉弹窗统一风格，并与按钮左对齐

## 2.9

- 修复切换服务器后首页仍显示旧服务器消息的问题
- 本地消息库按服务器隔离，避免消息 id 互相覆盖
- 详情页删除消息后首页回到第一页并重载

## 2.8

- 界面重构：卡片式列表、优先级色条、应用名胶囊，详情页首屏直接显示正文

## 2.7

- 修复详情页「消息不存在或已被清理」
- 历史消息同步入库；列表分页每页 30 条

## 2.5

- 首页支持按应用筛选

## 1.1

- 首个可用版本
