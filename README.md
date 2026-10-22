# Fiji 多图 Orthogonal Views

解决 ImageJ/Fiji 的 Orthogonal Views 只能保留一组 XY/XZ/YZ 视图的问题：每个源图像独立保留一组视图。

![Demo](assets/orthogonal-multi-demo.gif)

## 安装

### 1. 下载完整 ImageJ（Fiji，推荐）

**[下载完整 Fiji / ImageJ（Windows 64 位）](https://github.com/gouwenct/fiji-multi-orthogonal-views/releases/download/v0.1.0/fiji-multi-orthogonal-views-full-v0.1.0.zip)**

已集成多图 Orthogonal Views，解压即用。解压到新文件夹，运行 `Fiji.app/fiji-windows-x64.exe`，保留原有安装。

### 2. 为现有 Fiji 安装补丁（可选）

需要保留现有 Fiji 环境时，可[下载补丁包](https://github.com/gouwenct/fiji-multi-orthogonal-views/releases/download/v0.1.0/fiji-multi-orthogonal-views-patch-v0.1.0.zip)。解压后关闭 Fiji，在补丁目录运行：

```powershell
python tools/apply_patch.py "D:\Path\to\Fiji.app\jars\ij-1.54p.jar"
```

补丁仅适用于 [PATCH.md](PATCH.md) 指定的基础 JAR 哈希；脚本会先创建 `.original` 备份，哈希不匹配时停止。

## 让 Agent 安装

把下面提示词复制给有本地文件操作能力的 Agent：

```text
请帮我安装支持多图 Orthogonal Views 的 ImageJ（Fiji）：
https://github.com/gouwenct/fiji-multi-orthogonal-views/releases/tag/v0.1.0

默认下载 full 完整包，校验 Release 的 SHA256SUMS.txt 后解压到独立目录，
保留原有 Fiji 和数据。只有我明确要求给现有 Fiji 打补丁时，才下载 patch
补丁包，按 PATCH.md 校验基础 JAR 哈希并使用 tools/apply_patch.py 安装，
保留原 JAR 备份；哈希不匹配时停止。需要关闭 Fiji 时先提醒我保存图像。
安装后验证两个源图像能同时保留各自的 XY/XZ/YZ 视图，报告安装路径和结果。
```

## Git

```bash
git clone https://github.com/gouwenct/fiji-multi-orthogonal-views.git
```

这是非官方 Fiji 修改版。完整 Fiji 的原许可证和第三方声明保留在发行包中。
