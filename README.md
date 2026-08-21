# OplusRecentsFix（LSPosed 模块）

恢复 ColorOS 最近任务在三方桌面（Niagara 等）下被隐藏的 **浮窗 / 分屏** 入口。

## 背景与原理

- 使用三方桌面时，上滑唤出的最近任务仍是系统桌面 `com.android.launcher` 的
  quickstep 界面，但以 **FallbackRecentsActivity**（fallback 模式）承载。
- fallback 模式下 `ConfigParam.isMultiWindowMode()` 恒为 true，导致：
  - 浮窗：`RapidReactionUtils.isZoomWindowSupported()` 因 `inSplitScreenMode()==true` 返回 false；
  - 分屏：`SplitScreenShortcutPolity.isAvailable()` 第一条件直接失败。
- 本模块仅在宿主 activity 为 FallbackRecentsActivity 时改写这两个判定；
  系统桌面模式下逻辑完全不受影响；按应用的支持名单（ZoomWindowManager /
  isAppSupportSplitScreen）仍然保留。

## 构建（电脑端）

需要：Android Studio（含 Android SDK 34，JDK 17）。

1. 用 Android Studio 打开本目录（`oplus-recents-fix/`）。
2. 等待 Gradle 同步完成（会自动从 https://api.xposed.info/ 拉取 Xposed API 82）。
3. `Build > Build App Bundle(s) / APK(s) > Build APK(s)`，
   产物在 `app/build/outputs/apk/debug/app-debug.apk`。
   命令行等价：本机安装 Gradle 8.7+ 后执行 `gradle assembleDebug`。

## 部署

1. `adb install app-debug.apk`（或拷贝到手机安装）。
2. LSPosed 管理器 → 模块 → 启用 **OplusRecentsFix**；
   作用域勾选 **系统桌面**（模块已声明 xposedscope，通常会默认提示）。
3. 重启手机，或对“系统桌面”强行停止一次。
4. 验证：上滑进最近任务 → 卡片右上角 ⋮ → 应出现 **浮窗** 和 **分屏**；
   聚焦卡片顶部的快捷操作条同样恢复。

## 排错

- LSPosed 管理器 → 日志，搜索 `[OplusRecentsFix]`：
  - `float-window hook installed` / `split-screen hook installed` 表示 hook 成功；
  - `split recompute: ... => true/false` 是每次打开菜单时的逐项判定结果。
- 系统桌面 OTA 大版本更新后若失效，需重新核对下列类名/方法名
  （位于系统桌面 classes5.dex，可用 jadx 查看）：

| 作用 | 类 | 方法 |
|---|---|---|
| 浮窗判定 | com.oplus.quickstep.rapidreaction.utils.RapidReactionUtils | inSplitScreenMode(BaseActivity) |
| 分屏判定 | com.oplus.quickstep.multiwindow.splitscreen.SplitScreenShortcutPolity | isAvailable(BaseDraggingActivity, Task$TaskKey) |
| 分屏辅助 | com.oplus.quickstep.multiwindow.MultiWindowManager | getSplitScreenInfoProvider / isInSplitScreenMode(Integer) |
| 浮窗入口 | com.oplus.quickstep.shortcuts.OplusTaskShortcutsFactory | isUnSupportedZoomWindow |

## 已知边界

- 真处于分屏/多窗状态时打开最近任务，菜单里可能多显示分屏项（原版此时隐藏），
  点击一般不会崩溃，属可接受的边缘行为。
- 堆叠/平铺布局样式（key_recent_style）在 fallback 下被忽略的问题**未**包含在本模块中。
