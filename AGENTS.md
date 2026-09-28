# 项目名称：EyeCare 20-20-20 护眼助手 (Android + Web Preview)

## 1. 项目架构与双端职责
本项目采用“本地 Web 模拟预览 + Android 原生无环境云端打包”模式，双端同处一个仓库，互不干扰：
1. **`preview/` (Web 仿真预览端)**：
   - 纯前端代码（HTML/CSS/JS），直接在电脑浏览器打开。
   - 模拟手机屏幕外壳、亮屏计时、全屏半透明遮罩与 20 秒远眺倒计时动效，用于零门槛调试 UI 风格与交互节奏。
2. **`app/` (Android 原生端)**：
   - Kotlin 原生实现。
   - 负责亮屏广播监听（`SCREEN_ON` / `SCREEN_OFF`）、前台保活服务、全屏系统悬浮遮罩（`TYPE_APPLICATION_OVERLAY`）。
3. **`.github/workflows/` (云端自动化打包)**：
   - 通过 GitHub Actions 自动编译生成 APK，电脑本地无需安装 Android Studio 或 SDK。

## 2. 核心业务逻辑 (纯静音、无震动)
- **仅计亮屏时间**：锁屏/黑屏时暂停计时，亮屏时继续累计。
- **自动循环触发**：
  - 累计亮屏满 20 分钟（预览或测试模式可设为 10 秒），屏幕弹出半透明深色悬浮遮罩；
  - 提示用户远眺，并开始 20 秒倒计时；
  - 20 秒倒计时结束，遮罩自动销毁，计时清零并静默进入下一轮循环。
- **零干扰提示要求**：**严禁任何声音播报，且不需要震动**。完全依赖视觉遮罩的出现与消失来实现阻断提醒。

## 3. 目录规范
```text
EyeCareApp/
├── .github/workflows/build.yml   # 云端打包流水线
├── app/                          # Android 原生代码
│   ├── src/main/java/...         # MainActivity.kt, EyeCareService.kt
│   └── src/main/AndroidManifest.xml
├── preview/                      # 本地 Web 仿真调试（浏览器直接双击查看）
│   ├── index.html                # 手机外壳与页面结构
│   ├── style.css                 # 遮罩色调与动效样式
│   └── app.js                    # 纯前端计时与弹窗交互逻辑
├── build.gradle                  # 根构建文件
└── AGENTS.md                     # 本需求规范文档