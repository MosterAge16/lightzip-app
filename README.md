# 轻解压 (LightZip)

安卓解压工具：内置**官方 7-Zip 26.06（7zz）** 与 **官方 unrar 7.3.2** 引擎，7z / ZIP / RAR 全格式解压，并支持"伪装图片包"（图片壳内嵌压缩包）的深度扫描与剥离。

> 无 Gradle 构建：仓库内 `build.sh` 使用 aapt2 → javac → d8 → zipalign → apksigner 直接打包签名。

## 功能

- **全格式解压**：7z / ZIP / RAR(1~5) / TAR / GZIP / XZ / ZSTD / CAB / ISO / MSI / DMG / NSIS / WIM 等
- **密码支持**：7z AES、ZIP ZipCrypto/AES、RAR 加密包；密码错误自动弹窗重试
- **伪装包剥离**：图片等伪装文件一键深度扫描（全文件搜索 9 种压缩格式签名）→ 从签名处剥离 → 正常解压；支持 PNG/JPG/GIF 等任意壳
- **分卷压缩包**：`.7z.001`、`.part1.rar`、老式 `.rar+.r00` 自动识别，缺卷提示
- **内容浏览**：包内文件列表（大小/压缩后/时间）、搜索过滤、勾选部分文件/目录解压
- **解压管理**：文件夹选择器选输出目录、同名文件夹开关、重名策略（覆盖/跳过/重命名）、磁盘空间预检
- **前台服务**：通知栏实时进度、可取消，切后台/锁屏解压不中断
- **暗色主题**：跟随系统深色模式
- **多种入口**：文件管理器/QQ/微信「打开方式」、「分享到」、应用内选择器
- **文件诊断**：7z 头完整性预检（识别传输截断）、伪装识别

## 引擎与许可

| 组件 | 版本 | 许可 |
|---|---|---|
| [7-Zip](https://www.7-zip.org/)（7zz） | 26.06 | LGPL-2.1 + BSD 3-Clause + BSD 2-Clause，含 unRAR 限制 |
| [unrar](https://www.rarlab.com/) | 7.3.2 | unRAR license（仅可用于 RAR 解压，不得重建 RAR 压缩算法） |

引擎以 arm64 预编译二进制随仓库提供（`engines/`），由官方源码 + Android NDK 交叉编译。

## 构建

依赖：JDK 17+、Android SDK（build-tools 与 platforms）。

```bash
bash build.sh
# 产物：dist/LightZip.apk
```

仓库不含签名密钥（`release.jks`），首次构建会自动生成本地测试密钥。
