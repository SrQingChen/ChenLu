# 贡献指南

感谢关注尘露（ChenLu）！这是一个免费、无广告、开源（GPL-3.0）的 Android 自动化连点器。

## 开发环境

- Android Studio 最新稳定版
- JDK 21+（项目按 AGP 9 / Kotlin 2.4 基线）
- Android SDK Platform 37（compileSdk 37 / targetSdk 36 / minSdk 26）
- 构建：`./gradlew assembleDebug`

## 模块结构

对齐 Google Now in Android 的多模块架构，convention plugins 统一构建配置（见 `build-logic/`）：

- `app`：壳工程，组装与导航
- `core/*`：基础层（common / model / designsystem）
- `engine/*`：输入引擎（api 抽象 + accessibility 实现；Shizuku 引擎规划中）
- `service`：前台服务、悬浮窗体系、任务控制器
- `feature/*`：Compose 界面层

模块只允许自上而下依赖；`engine`/`vision` 的实现模块只依赖各自 `api` 模块（可插拔的关键）。

## 提交规范

- 提交信息使用中文或英文均可，格式：`模块: 摘要`，如 `engine: 无障碍引擎支持长按手势`
- 提交前保证 `./gradlew assembleDebug testDebugUnitTest` 通过

## 许可

提交贡献即表示你同意以 GPL-3.0 许可发布你的贡献。
