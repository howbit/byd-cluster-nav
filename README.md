# 仪表导航助手 ClusterNav

ClusterNav 是针对一款已验证比亚迪 DiLink 3.0 车机的 Android 应用，用本机已授权的无线 ADB 和原厂高德地图接口控制仪表导航。它不需要 Root，不修改地图 APK，也不上传车机数据。当前版本为 **2.9.8**，包名为 `com.byd.clusternav.diagnostic`。

软件依赖厂商私有接口、系统固件和定制版高德，不能推断兼容所有比亚迪车型或其他地图。

## 功能和边界
- 仅支持18系列的控制器，即仪表盘有小地图导航的车机。
- 自动或手动开启全屏仪表导航，可随时请求关闭，并读回原厂导航状态。
- 首次开启时通过厂商 `AutoContainer` 通道发送 `16`，等待至少 6 秒后发送 `35`，再写入导航属性 `4`、识别真实副屏、启动原厂高德。仪表分屏使用 `17`；关闭时写导航属性 **1** 并向 container 发送 **18**。
- 副屏和地图窗口均在时直接接管；只有副屏仍在而地图窗口消失时，会复核模式和副屏身份后接管原通道，再启动地图。检测到窗口和 Surface 并不等于证明仪表上的地图像素正常。
- 开机自启由用户设置控制。广播快速转交 `BootStartupService`，服务最多等待本机 ADB 两分钟，再交给导航会话。用户主动关闭会取消尚未完成的开机等待。
- 一键配置可尝试 DeviceIdle、AppOps、自启动管理及应用加速管理。自启动管理页的开关 **ON=禁止后台自启**，应用加速页的开关 **ON=开启保护**。仅在明确读回目标状态后记录成功；无法判断时显示未验证。本次配置读回后显示时间与本次结果；重新打开应用进程后仅显示上次结果，并标明本次尚未复核。
- 诊断 ZIP 保存显示、窗口、地图服务等快照，以及按关键词筛选的日志。它不包含完整 `dmesg` 或完整 `logcat`，也不包含地图数据。
- 两个页面的标题栏都可打开 [GitHub 开源主页](https://github.com/howbit/byd-cluster-nav)；车机需有能够处理 HTTPS 链接的应用。


## 安装和使用

1. 仅在已确认兼容的车机上安装 APK，开启并授权本机 `127.0.0.1:5555` 无线 ADB。
2. 在应用中检测 ADB、厂商通道、导航状态及地图安装情况。先确认本机已安装带 `com.byd.automap.extra.MeterActivity` 的兼容原厂地图。
3. 如需后台自启，使用一键配置后逐项核对状态。系统设置界面及文案可能随固件不同而变化；“上次读回”不是“当前实时状态”。
4. 选择全屏导航。若窗口已经出现而仪表没有实际地图，导出诊断 ZIP 并记录当时画面；先不要把窗口状态当成出图证明。
5. 关闭时使用应用内“关闭仪表地图”，再核对仪表实际画面。菜单中保留“全屏导航”选项不代表导航仍在运行。

现有安装若使用不同签名，Android 无法直接覆盖升级；需要使用原签名密钥构建更新包。公开源码包**不包含**私有签名密钥或 ADB 私钥。

## 从源码构建

需要 Python 3、JDK 17、Android SDK Platform 29 和 Build Tools 35.0.0。`build.py` 使用 `aapt`、`javac`、`d8`、`zipalign`、`apksigner`，不依赖 Gradle。先在本机环境中设置：

| 环境变量 | 内容 |
| --- | --- |
| `ANDROID_SDK_ROOT` | 本机 Android SDK 目录 |
| `JAVA_HOME` | JDK 17 目录 |
| `SIGNING_KEYSTORE` | 你自己创建的 keystore 绝对路径 |
| `SIGNING_PASSWORD` | 该 keystore 和密钥的密码；不要写进仓库 |
| `SIGNING_ALIAS` | 密钥别名，默认为 `clusternav` |
| `OUTPUT_APK` | 可选，默认输出到 `build/clusternav-v2.9.8.apk` |
| `BUILD_TOOLS_VERSION` | 可选，默认 `35.0.0` |

创建个人签名密钥时可运行 `keytool -genkeypair -keystore <你的路径> -alias clusternav -keyalg RSA -keysize 3072 -validity 10000`，交互式设置密码并妥善保存。随后在项目根目录执行：

```text
python build.py
```

构建脚本验证 APK 签名和对齐，并运行纯 Java 回归测试。`test_guard.py` 和 `test_map_restart.py` 使用单独的模拟器测试 DEX；它们硬性要求 `emulator-5580` 且会改动该模拟器中的测试状态。不要把模拟器测试脚本指向实车。测试地图夹具与私有厂商调用的真实兼容性是不同的证据。

模拟器开机接收器与等待服务可用 `python test_android.py --boot-only` 检查。创建可发布的源码归档可用 `python tools/package_release.py --output <ZIP 路径>`；脚本只收录明确列出的源码、资源、测试和文档，并为每个文件生成 SHA-256 清单。

## 测试与项目结构

| 路径 | 用途 |
| --- | --- |
| `src/com/byd/clusternav/core/` | 导航状态机、厂商桥接、开机与配置流程 |
| `src/com/byd/clusternav/adb/` | 本机 ADB 客户端和安装实例自己的授权密钥 |
| `src/com/byd/clusternav/ui/` | 应用主界面和色卡测试界面 |
| `tests/CoreTest.java` | 纯 Java 核心逻辑断言 |
| `test_guard.py` | 模拟器中的会话守护、关闭和权限边界测试 |
| `test_map_restart.py` | 模拟副屏、地图重启、现有通道接管测试 |
| `test_android.py --boot-only` | 模拟器中的开机广播与独立前台服务测试 |
| `HANDOVER.md` | 维护所需的协议与验证证据 |

发布前将车机日志中的车牌、位置、设备标识、账户与 ADB 授权信息脱敏。`build/`、诊断 ZIP、签名文件和个人环境配置均不属于公开源码。

## 许可证与关系说明

本项目源码采用 [Apache License 2.0](LICENSE)。比亚迪、DiLink、高德等名称用于说明兼容对象；本项目与相关厂商无隶属或官方授权关系，仓库不分发其 APK、系统框架、解包源码或商标素材。
