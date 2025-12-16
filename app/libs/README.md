# Sing-box Libbox AAR

本目录用于存放 sing-box 的 Android 核心库 (libbox AAR)。

## 获取 libbox AAR

有两种方式获取 libbox AAR:

### 方式一：从官方 SFA APK 提取

1. 从 [sing-box releases](https://github.com/SagerNet/sing-box/releases) 下载最新的 `SFA-x.x.x-universal.apk`
2. 将 APK 文件重命名为 `.zip` 后缀并解压
3. 在解压目录中找到 `lib/` 文件夹下的 `.so` 文件
4. 使用这些 `.so` 文件创建 AAR 包

### 方式二：从源代码编译 (推荐)

1. 克隆 sing-box 仓库:
   ```bash
   git clone https://github.com/SagerNet/sing-box.git
   cd sing-box
   ```

2. 安装 Go 和 gomobile:
   ```bash
   go install golang.org/x/mobile/cmd/gomobile@latest
   gomobile init
   ```

3. 编译 libbox:
   ```bash
   make lib_android
   ```

4. 编译完成后，将生成的 `libbox.aar` 复制到本目录

### 方式三：使用预编译版本

如果你不想自己编译，可以尝试搜索社区提供的预编译版本，但请注意安全风险。

## 版本兼容性

请确保使用的 libbox 版本与你的 sing-box 配置格式兼容。推荐使用最新稳定版本。

当前推荐版本: **1.12.13**

## 文件命名

将下载或编译的 AAR 文件放置在本目录，命名为:
- `libbox.aar` 或
- `libbox-1.12.13.aar` (带版本号)

## 注意事项

- libbox AAR 包含原生 `.so` 库，大小约 60-70MB (universal)
- 如果只需要特定架构，可以使用对应架构的 APK 来减小体积