# BackTapHTTP

BackTapHTTP 是一个 Android 工具应用：**利用小米 / HyperOS 的背部轻敲动作，触发一条自定义 HTTP 请求**。

它适合做这些事情：

- **智能家居触发**
- **Webhook 自动化**
- **局域网接口调用**
- **个人服务器快捷控制**

## 功能特性

- **背部轻敲触发 HTTP 请求**
- **支持 `GET` / `POST` / `PUT` / `DELETE` / `PATCH`**
- **支持自定义 Header**
- **支持 JSON / Form / 空 Body**
- **请求日志持久化保存，重启后不丢失**
- **前台服务保活，降低被系统回收概率**
- **可在 App 内手动切换静音，且不会误触发请求**
- **适配 MIUI / HyperOS 的保活设置入口**

## 工作原理

1. 在系统中把背部轻敲动作设置为“静音”
2. 系统切换静音时，会发出 `RINGER_MODE_CHANGED` 广播
3. App 的 `AccessibilityService` 监听该变化
4. 检测到触发后，App 会：
   - 立即把静音状态反转回去，尽量减少用户感知
   - 按你保存的配置发送 HTTP 请求

## 项目结构

```text
app/src/main/java/com/backtap/httpfire/
├── MainActivity.kt                 # 主界面：配置、状态、日志、手动静音
├── BackTapAccessibilityService.kt  # 核心服务：监听静音变化、反转状态、发起请求
├── HttpSender.kt                   # HTTP 配置存储与请求发送
└── LogStore.kt                     # 请求日志持久化存储
```

## 运行环境

- **Android Studio**：Ladybug / Koala 或兼容版本
- **Android SDK**：`compileSdk 34`
- **最低系统版本**：Android 8.0 (`minSdk 26`)
- **推荐设备**：支持背部轻敲并允许将该动作设为静音的小米 / HyperOS 设备

## 快速开始

### 1. 克隆项目

```bash
git clone <your-repo-url>
```

### 2. 使用 Android Studio 打开项目

直接打开项目根目录 `BackTapHTTP`。

### 3. 构建 APK

- Android Studio: `Build > Build APK(s)`
- 或命令行：

```bash
./gradlew assembleDebug
```

Windows：

```powershell
.\gradlew.bat assembleDebug
```

### 4. 安装到手机

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 使用说明

### 1. 设置背部轻敲动作

在系统中将背部轻敲动作设置为“静音”。

部分设备也可以尝试使用：

```bash
adb shell settings put system back_double_tap "mute"
```

是否生效取决于机型和系统版本。

### 2. 开启无障碍服务

打开 App，点击“开启无障碍”，找到 **BackTapHTTP** 并启用。

### 3. 配置请求

在 App 中填写：

- **URL**：目标接口地址
- **Method**：请求方法
- **Headers**：每行一个 `Key: Value`
- **Body Type**：无 / JSON / 表单
- **Body**：请求体内容

然后点击“保存配置”。

### 4. 测试发送

点击“测试发送”，确认接口可用。

### 5. 实际触发

背部轻敲后，App 会自动发送请求，并在日志区记录：

- 时间
- 请求方法
- URL
- 状态码
- 耗时

## 权限说明

项目使用了以下关键权限：

- `BIND_ACCESSIBILITY_SERVICE`
  - 用于监听辅助功能服务
- `MODIFY_AUDIO_SETTINGS`
  - 用于恢复静音状态
- `ACCESS_NOTIFICATION_POLICY`
  - Android 7+ 手动切换静音时需要
- `FOREGROUND_SERVICE`
  - 保持核心服务更稳定运行
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
  - 引导用户关闭电池优化
- `INTERNET`
  - 发送 HTTP 请求

## 保活说明

在 MIUI / HyperOS 上，系统可能会更激进地回收后台服务。建议在首次安装后额外完成：

1. 开启 App 自启动
2. 关闭电池优化，改为“无限制”
3. 将 App 在最近任务中加锁

App 内已提供“保活设置”入口辅助跳转。

## 已知限制

- 该方案**依赖系统把背部轻敲映射为静音动作**
- 不同机型、不同 HyperOS / MIUI 版本的行为可能不同
- 某些系统版本会更严格限制后台能力，仍需用户手动配置保活
- 如果系统禁止静音控制或勿扰权限未授予，手动静音按钮可能无法工作

## 日志说明

- 请求日志会持久化保存
- 重启 App 后仍可查看最近日志
- 日志数量有上限，超出后会自动清理旧记录

## 开源建议

如果你准备公开到 GitHub，建议额外补充：

- `LICENSE`
- Release 截图
- 示例配置说明
- 常见问题（FAQ）

## 免责声明

本项目仅供学习与个人自动化使用。请确保你发送请求的目标服务、设备和 API 符合当地法律法规及平台使用政策。
