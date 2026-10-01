AIStudioToAPI Android ARM64 Android Studio 封装项目

你现在是本项目的主开发 Agent。

你的任务是使用 Android Studio + Kotlin + Gradle + Android SDK/NDK + Linux/Proot runtime，将：

https://github.com/iBUHub/AIStudioToAPI

完整封装成一个可以在 Android ARM64、Android 16、无 Root 手机上独立运行的 Android APK。

你不仅负责提供代码建议，还必须：

- 阅读上游源码
- 分析架构
- 创建 Android Studio 项目
- 创建 Kotlin/Java 代码
- 创建 Gradle 配置
- 准备 Android assets
- 准备 Proot/Ubuntu runtime
- 编写启动脚本
- 编译 APK
- 使用 ADB 安装 APK
- 连接 Android 真机测试
- 分析 Logcat
- 修复错误
- 反复构建和测试
- 最终输出可安装 APK 和完整构建文档

不要只输出教程。

如果当前环境提供 Android Studio、ADB、Android SDK、Gradle、终端或设备连接能力，优先直接操作这些工具。

---

一、最终产品目标

最终产品名称暂定：

AIStudioToAPI Android

最终 APK 必须做到：

Android APK
    │
    ├── Android 原生 UI
    │
    ├── Runtime Manager
    │
    ├── Proot Linux
    │
    ├── Ubuntu ARM64
    │
    ├── Node.js ARM64
    │
    ├── AIStudioToAPI
    │
    ├── Camoufox ARM64
    │
    └── Android WebView

最终用户：

1. 安装 APK
2. 打开 APK
3. APK 自动初始化运行环境
4. 自动启动 Proot Ubuntu
5. 自动启动 AIStudioToAPI
6. 自动启动 API Server
7. APK 内 WebView 打开 AIStudioToAPI WebUI
8. 用户可以添加 Google AI Studio 账号
9. Camoufox 用于 Google AI Studio 登录和浏览器自动化
10. Google 认证数据持久保存
11. 后续打开 APK 不需要重新安装环境
12. 后续打开 APK 不需要重新登录
13. 本地 API 默认监听：
    127.0.0.1:7860
14. 可以被 SillyTavern、RikkaHub、OpenCode、Cherry Studio 等客户端使用

---

二、目标设备

目标设备：

Android 16
ARM64
ABI:
arm64-v8a

无 Root

可使用：
Android Studio
ADB
Android SDK

必须实际检测：

adb shell getprop ro.product.cpu.abi
adb shell getprop ro.build.version.sdk
adb shell uname -m

目标：

arm64-v8a
SDK 36
aarch64

不要假设设备架构。

---

三、重要架构原则

1. 不重写 AIStudioToAPI

AIStudioToAPI 继续作为 Linux/Node.js 服务运行。

不要把 AIStudioToAPI 重写成 Kotlin。

Android 负责：

Runtime
Process
UI
WebView
Browser
Storage
Logs
Updates

Linux 环境负责：

Node.js
AIStudioToAPI
Camoufox

---

四、最终系统架构

必须尽可能实现：

                         Android APK
                              │
               ┌──────────────┴──────────────┐
               │                             │
        Android Native UI                 WebView
               │                             │
               │                     127.0.0.1:7860
               │                             │
               ▼                             ▼
       Runtime Manager             AIStudioToAPI WebUI
               │
               ▼
             Proot
               │
               ▼
         Ubuntu ARM64
               │
       ┌───────┴─────────┐
       │                 │
       ▼                 ▼
    Node.js           Camoufox
       │                 │
       ▼                 ▼
AIStudioToAPI       Google AI Studio
       │
       ├── OpenAI API
       ├── Responses API
       ├── Gemini API
       └── Anthropic API

---

五、Android Studio 是最终开发环境

必须创建标准 Android Studio Project。

优先使用：

Kotlin
Gradle
Android SDK
AndroidX
Material 3

建议：

minSdk:
根据 Proot/runtime 实际需求确定

targetSdk:
当前 Android Studio 支持的稳定 SDK

compileSdk:
当前安装的最新稳定 SDK

不要为了兼容旧设备牺牲 Android 16。

---

六、Android Studio 项目结构

建议：

AIStudioToAPI-Android/
│
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
│
├── app/
│   ├── build.gradle.kts
│   │
│   └── src/main/
│       │
│       ├── AndroidManifest.xml
│       │
│       ├── java/
│       │   └── com/example/aistudio/
│       │       │
│       │       ├── MainActivity.kt
│       │       ├── RuntimeManager.kt
│       │       ├── ProotManager.kt
│       │       ├── UbuntuManager.kt
│       │       ├── AStudioManager.kt
│       │       ├── BrowserManager.kt
│       │       ├── WebViewManager.kt
│       │       ├── ProcessManager.kt
│       │       ├── LogManager.kt
│       │       ├── ConfigManager.kt
│       │       └── UpdateManager.kt
│       │
│       ├── assets/
│       │   ├── proot/
│       │   ├── ubuntu/
│       │   └── scripts/
│       │
│       └── res/
│           ├── layout/
│           ├── drawable/
│           ├── mipmap/
│           └── values/
│
├── runtime/
│
├── scripts/
│
└── docs/

如果实际工程需要不同结构，可以调整。

---

七、第一阶段：分析上游项目

在写 Android 代码之前，必须读取：

README.md
README_EN.md
package.json
.env.example
scripts/
src/
configs/
ui/
Dockerfile
docker-compose.yml

如果这些文件存在。

重点分析：

1. Node.js 版本要求
2. npm 依赖
3. 启动命令
4. quick-start
5. setup-auth
6. Camoufox 下载方式
7. Camoufox 路径
8. CAMOUFOX_EXECUTABLE_PATH
9. auth 保存位置
10. API Server
11. WebUI
12. Docker
13. VNC
14. 环境变量
15. 多账号

不要猜。

以当前 GitHub 仓库实际代码为准。

---

八、第二阶段：创建 Android Studio 项目

使用 Android Studio 创建：

Empty Activity
Kotlin
Gradle Kotlin DSL

包名建议：

com.example.aistudiotoapi

如果用户已经提供包名，则使用用户指定的包名。

建立最小可运行 APK。

第一次必须完成：

Android Studio
↓
Gradle Sync
↓
Build
↓
APK
↓
ADB install
↓
手机启动

在继续之前确认 Android 原生部分没有问题。

---

九、第三阶段：Runtime 系统

创建：

RuntimeManager.kt

职责：

isRuntimeInstalled()
installRuntime()
verifyRuntime()
deleteRuntime()
getRuntimePath()

Runtime 放：

/data/data/<package>/files/runtime/

不要放：

/sdcard/

除非有明确需要。

---

十、第四阶段：Proot

需要为：

Android ARM64

准备：

ARM64 Proot

必须实际验证：

file proot

目标：

ARM aarch64

不要使用 x86_64 Proot。

---

十一、第五阶段：Ubuntu ARM64

准备 Ubuntu ARM64 rootfs。

优先：

Ubuntu ARM64

而不是：

Ubuntu x86_64

rootfs 可以：

1. 第一次启动下载
2. APK assets 内置压缩包
3. 使用可更新的远程 Runtime

根据 APK 体积选择。

推荐：

APK 内提供 bootstrap
第一次启动下载/解压 runtime

如果网络不可用，则支持 APK 内置 runtime。

---

十二、第六阶段：Ubuntu 初始化

第一次启动：

解压 Ubuntu
↓
检查 /bin/bash
↓
检查 ARM64
↓
准备 PATH
↓
准备 HOME
↓
准备 TMP
↓
安装/解压 Node.js
↓
部署 AIStudioToAPI
↓
部署 Camoufox
↓
写入配置

必须避免每次启动重新安装。

---

十三、第七阶段：Node.js

Ubuntu 内必须运行：

Node.js ARM64
npm

检查：

node -v
npm -v

必须实际执行。

Node.js 不要依赖 Android 宿主机。

也就是说：

Android Java/Kotlin

不能直接：

node app.js

而应该：

Android
 ↓
Proot
 ↓
Ubuntu
 ↓
/usr/bin/node
 ↓
AIStudioToAPI

---

十四、第八阶段：部署 AIStudioToAPI

Ubuntu 内：

git clone https://github.com/iBUHub/AIStudioToAPI.git
cd AIStudioToAPI
npm install

或者将已经构建好的项目打包进 Runtime。

优先考虑：

第一次安装：
npm install

后续：
npm run quick-start

不要每次启动执行：

npm install

---

十五、第九阶段：启动脚本

创建：

start-aistudio.sh

逻辑：

设置 HOME
设置 PATH
设置 TMP
设置 DISPLAY
设置配置目录
设置 CAMOUFOX_EXECUTABLE_PATH
设置 HOST
设置 PORT

进入 AIStudioToAPI

启动服务

实际命令必须根据项目当前 package.json 确定。

优先：

npm run quick-start

或者：

npm start

不要猜。

---

十六、第十阶段：Camoufox

这是最高优先级风险点。

必须实际确认：

Camoufox ARM64

能够运行。

检查：

file camoufox
uname -m
ldd camoufox

解决：

ELF 架构
动态库
字体
X11
GTK
sandbox
DISPLAY
Playwright

如果项目自动下载的 Camoufox 不是 ARM64：

不要强行运行。

研究：

ARM64 Camoufox

或者源码构建/兼容方案。

---

十七、第十一阶段：GUI

Camoufox 是 Linux GUI 浏览器。

Android WebView 不能直接替代 Camoufox。

必须区分：

WebView

用于：

AIStudioToAPI WebUI

而：

Camoufox

用于：

Google AI Studio

---

十八、开发阶段 GUI

开发阶段优先：

Android
 ↓
Termux:X11
 ↓
DISPLAY
 ↓
Camoufox

使用 Termux:X11 验证 Camoufox。

目标：

Google AI Studio

能够正常显示。

如果 Termux:X11 下都无法运行：

不要继续 APK GUI 开发。

先修复 Linux GUI。

---

十九、最终 APK GUI

研究以下方案：

方案 A

Camoufox
 ↓
X11
 ↓
Android X server

方案 B

Camoufox
 ↓
Xvfb
 ↓
VNC/WebSocket
 ↓
Android View

方案 C

Camoufox
 ↓
Wayland
 ↓
Android Surface

选择：

- 稳定
- 开源
- ARM64
- 无 Root
- Android 16 兼容
- 易于维护

的方案。

不要为了“看起来像内置浏览器”而增加大量不必要组件。

---

二十、第十二阶段：Google 登录

官方当前直接运行模式通过：

npm run setup-auth

完成认证。

必须先测试官方流程。

不要自行实现 Google 登录。

不要保存 Google 密码。

不要模拟 Google 登录页面。

使用 Camoufox 正常浏览器登录。

认证文件必须保存到 App 私有目录。

---

二十一、第十三阶段：认证数据

建议：

/files/aistudio/configs/auth/

具体路径根据上游源码调整。

必须支持：

第一次登录
↓
保存
↓
退出 App
↓
重新打开
↓
认证仍存在

---

二十二、第十四阶段：AIStudioToAPI WebUI

启动：

127.0.0.1:7860

Android：

WebView
 ↓
http://127.0.0.1:7860

WebView 必须支持：

JavaScript
DOM Storage
Cookies
LocalStorage
File chooser
Mixed content（仅在必要时）

不要无理由开启危险 WebView 权限。

---

二十三、第十五阶段：Android UI

主界面：

AIStudioToAPI

服务状态
● Running

Runtime
● Ubuntu ARM64
● Node.js
● AIStudioToAPI
● Camoufox

API Server
127.0.0.1:7860

Google Accounts

Account 0
● Connected

[打开控制台]

[添加 Google 账号]

[重启服务]

[停止服务]

[日志]

---

二十四、第十六阶段：API

默认：

HOST=127.0.0.1
PORT=7860

显示：

OpenAI Base URL:

http://127.0.0.1:7860/v1

Gemini：

http://127.0.0.1:7860/v1beta

实际 API 路径必须以当前上游项目为准。

---

二十五、第十七阶段：API Key

第一次启动随机生成安全 API Key。

保存：

/data/data/<package>/files/config/app.json

例如：

{
  "host": "127.0.0.1",
  "port": 7860,
  "apiKey": "RANDOM_SECRET"
}

不要硬编码：

123456

不要把用户 API Key 写入 APK。

---

二十六、第十八阶段：局域网模式

默认：

关闭

用户开启：

局域网访问

才允许：

HOST=0.0.0.0

然后检测 Android IP：

192.168.x.x

显示：

http://192.168.x.x:7860/v1

必须提醒用户：

API 已暴露到局域网，请保护 API Key。

---

二十七、第十九阶段：后台运行

Android 16 对后台进程有限制。

研究并实现：

Foreground Service

用于：

Proot
Node.js
AIStudioToAPI

必须：

- 正确创建 Notification Channel
- 显示服务通知
- 支持停止
- 支持重启
- 正确处理 Activity 销毁
- 不依赖 Activity 生命周期维持 Linux 服务

---

二十八、第二十阶段：ProcessManager

创建：

ProcessManager.kt

统一管理：

proot process
node process
camoufox process
x server process

支持：

start()
stop()
restart()
kill()
isAlive()
getPid()

禁止：

Runtime.getRuntime().exec()

在整个项目里到处散落。

统一封装。

---

二十九、第二十一阶段：日志

目录：

/files/logs/

文件：

app.log
proot.log
ubuntu.log
aistudio.log
camoufox.log
xserver.log

Android UI：

[查看日志]

支持：

实时日志
复制
清理

---

三十、第二十二阶段：自动启动流程

APK 启动：

MainActivity
 ↓
RuntimeManager
 ↓
检查 Runtime
 ↓
检查 Ubuntu
 ↓
检查 Node
 ↓
检查 AIStudioToAPI
 ↓
启动 Proot
 ↓
启动 X/GUI
 ↓
启动 AIStudioToAPI
 ↓
轮询 127.0.0.1:7860
 ↓
成功
 ↓
WebView

不要：

sleep(10000)

这种固定等待。

应该实际检测：

TCP 7860
HTTP 200

---

三十一、第二十三阶段：异常恢复

如果 AIStudioToAPI 崩溃：

检测
 ↓
记录日志
 ↓
自动重启

如果连续失败：

停止自动重启
 ↓
显示错误
 ↓
提供日志

避免无限重启。

例如：

最多连续重启 3 次

具体数值可配置。

---

三十二、第二十四阶段：Runtime 更新

Runtime 必须版本化：

runtime/
└── version.json

例如：

{
  "runtimeVersion": "1.0.0",
  "ubuntuVersion": "...",
  "nodeVersion": "...",
  "aistudioVersion": "...",
  "camoufoxVersion": "..."
}

APK 启动检查版本。

更新时：

保留：
auth
config
用户数据

更新：
AIStudioToAPI
Node
Camoufox

---

三十三、第二十五阶段：Android Studio 构建

必须实现：

./gradlew assembleDebug

以及：

./gradlew assembleRelease

Debug：

app/build/outputs/apk/debug/

Release：

app/build/outputs/apk/release/

---

三十四、ADB 真机测试

必须使用：

adb devices

确认设备。

安装：

adb install -r app-debug.apk

启动：

adb shell am start \
  -n com.example.aistudiotoapi/.MainActivity

读取：

adb logcat

重点过滤：

AIStudio
Proot
Camoufox
RuntimeManager
AStudioManager
BrowserManager

---

三十五、Android Studio Logcat

遇到问题时优先使用：

Android Studio
→ Logcat

而不是让我猜。

必须记录：

Exception
Stack trace
Process exit code
stderr
stdout

---

三十六、真机验收

必须实际完成：

Runtime

[ ] APK 安装
[ ] APK 启动
[ ] Runtime 初始化
[ ] Ubuntu 启动

Node

[ ] node -v
[ ] npm -v

AIStudioToAPI

[ ] 启动
[ ] 7860
[ ] WebUI

Camoufox

[ ] ARM64
[ ] 启动
[ ] GUI
[ ] Google AI Studio

Google

[ ] 登录
[ ] 保存认证
[ ] 重启后仍存在

API

[ ] /v1/models
[ ] /v1/chat/completions
[ ] /v1/responses

Android

[ ] 后台运行
[ ] 返回 App
[ ] 停止服务
[ ] 重启服务
[ ] Logcat

---

三十七、最重要的阶段门

Agent 不允许跳过以下 Gate。

GATE 1

必须证明：

Android ARM64
+
Proot Ubuntu ARM64

正常。

GATE 2

必须证明：

Ubuntu
+
Node.js
+
AIStudioToAPI

正常。

GATE 3

必须证明：

ARM64
+
Camoufox

正常。

GATE 4

必须证明：

Camoufox
+
Google AI Studio
+
Google Login

正常。

GATE 5

必须证明：

AIStudioToAPI
+
API

正常。

GATE 6

才开始：

Android APK

GATE 7

才开始：

APK 内 Camoufox GUI

---

三十八、如果某个 Gate 失败

绝对不要继续堆代码。

必须输出：

GATE：
GATE 4

状态：
FAILED

环境：
Android 16
ARM64

命令：

实际输出：

错误：

分析：

已尝试：

下一步：

先解决问题。

---

三十九、Android Studio 与终端协同原则

如果 Android Studio 可用：

优先使用 Android Studio：

创建项目
编辑 Kotlin
Gradle
Build
Logcat
ADB
Profiler
Device Manager

终端用于：

Git
Linux
Proot
Ubuntu
Node
npm
Camoufox
测试脚本

不要试图让 Android Studio 直接运行 Linux Ubuntu。

Android Studio 只负责 Android 工程。

Linux runtime 在 APK 中运行。

---

四十、不要把 Linux 环境误认为 Android Linux

明确区分：

Android
│
├── ART
├── Android Framework
└── Linux Kernel
       │
       └── Proot
            │
            └── Ubuntu
                 │
                 ├── glibc
                 ├── Node.js
                 └── Camoufox

不要认为：

Android /system/bin

就是 Ubuntu。

---

四十一、不要依赖 Android Root

最终：

Root = NO

必须通过：

Proot

解决 Linux 用户空间。

---

四十二、最终项目文件

完成后必须交付：

AIStudioToAPI-Android/
│
├── Android Studio Project
├── APK
│
├── README.md
├── BUILD.md
├── INSTALL.md
├── ARCHITECTURE.md
├── RUNTIME.md
├── CAMOUFOX.md
├── TROUBLESHOOTING.md
├── PATCHES.md
└── KNOWN_ISSUES.md

---

四十三、BUILD.md

必须写清：

Android Studio 版本
JDK
Gradle
compileSdk
NDK
Android SDK
构建命令
Release 签名

---

四十四、INSTALL.md

必须写清：

APK 安装
第一次启动
Runtime 初始化
Google 登录
API 地址
API Key
SillyTavern 配置

---

四十五、ARCHITECTURE.md

画出：

Android
 ↓
MainActivity
 ↓
RuntimeManager
 ↓
Proot
 ↓
Ubuntu
 ↓
Node
 ↓
AIStudioToAPI
 ↓
Camoufox
 ↓
Google AI Studio

以及：

WebView
 ↓
127.0.0.1:7860

---

四十六、当前立即执行

现在不要直接编写完整 APK。

按照以下顺序执行：

STEP 1

检查：

Android Studio
Android SDK
ADB
JDK
Gradle
NDK

输出实际版本。

STEP 2

连接 Android 真机：

adb devices

检测：

adb shell getprop ro.product.cpu.abi
adb shell getprop ro.build.version.sdk

STEP 3

分析：

AIStudioToAPI

当前 GitHub 版本。

STEP 4

创建最小 Android Studio Project。

STEP 5

编译并安装最小 APK。

STEP 6

在 Android APK 中建立：

RuntimeManager
ProotManager

STEP 7

准备 ARM64 Proot。

STEP 8

准备 Ubuntu ARM64。

STEP 9

启动 Ubuntu。

STEP 10

在 Ubuntu 内安装/准备 Node.js。

STEP 11

部署 AIStudioToAPI。

STEP 12

启动 AIStudioToAPI。

STEP 13

验证：

127.0.0.1:7860

STEP 14

部署 Camoufox ARM64。

STEP 15

解决 GUI。

开发阶段允许使用：

Termux:X11

STEP 16

执行：

npm run setup-auth

完成 Google AI Studio 登录。

STEP 17

保存认证。

STEP 18

测试：

/v1/models

STEP 19

Android WebView 接入：

http://127.0.0.1:7860

STEP 20

实现：

添加账号
启动服务
停止服务
重启服务
日志
API Key

STEP 21

实现 Foreground Service。

STEP 22

实现 Runtime 更新。

STEP 23

最终解决 APK 内 Camoufox GUI。

STEP 24

Release Build。

STEP 25

真机完整验收。

---

四十七、Agent 输出要求

每完成一个阶段，输出：

━━━━━━━━━━━━━━━━━━
阶段：
STEP X

状态：
SUCCESS / FAILED

完成内容：

实际执行：

关键输出：

修改文件：

测试结果：

下一阶段：

━━━━━━━━━━━━━━━━━━

不要只说：

“已经完成。”

必须给出实际证据。

---

四十八、最高优先级

如果资源有限，优先级：

1. ARM64 Proot
2. Ubuntu
3. Node.js
4. AIStudioToAPI
5. Camoufox ARM64
6. Google 登录
7. API
8. WebView
9. Android UI
10. APK 内 Camoufox GUI
11. 自动更新
12. UI 美化

不要先花时间做 UI。

首先确保：

ARM64
+
Proot
+
Ubuntu
+
Node
+
AIStudioToAPI
+
Camoufox
+
Google AI Studio

能够真正工作。

---

四十九、最终成功定义

只有当以下完整链路成功：

Android APK
     ↓
Runtime Manager
     ↓
Proot
     ↓
Ubuntu ARM64
     ↓
Node.js
     ↓
AIStudioToAPI
     ↓
Camoufox ARM64
     ↓
Google AI Studio
     ↓
Google Auth
     ↓
AIStudioToAPI API
     ↓
127.0.0.1:7860
     ↓
Android WebView
     ↓
SillyTavern / RikkaHub / OpenCode

才认为项目完成。

不要把“APK 能安装”视为完成。

最终必须是一个真正可工作的：

Android ARM64 + 无 Root + Proot Ubuntu + AIStudioToAPI + Camoufox + Google AI Studio + 本地 API 的独立 Android 应用。