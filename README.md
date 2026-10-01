# Fiji 多图 Orthogonal Views

解决 ImageJ/Fiji 的 Orthogonal Views 只能保留一组 XY/XZ/YZ 视图的问题：每个源图像独立保留一组视图。

![Demo](assets/orthogonal-multi-demo.gif)

## 安装

推荐下载 Releases 中的完整 Fiji 压缩包，解压后运行 `Fiji.app/fiji-windows-x64.exe`。

也可以下载补丁包，关闭 Fiji 后运行：

```powershell
python tools/apply_patch.py "D:\\Path\\to\\Fiji.app\\jars\\ij-1.54p.jar"
```

补丁只适用于 README 中列出的基础 JAR 哈希；脚本会先创建 `.original` 备份。

## 让 Agent 安装

把下面提示词复制给有本地文件操作能力的 Agent：

```text
请安装 Fiji 多图 Orthogonal Views 补丁：
https://github.com/gouwenct/fiji-multi-orthogonal-views/releases/tag/v0.1.0

先定位我的 Fiji.app 路径并确认版本；关闭正在运行的 Fiji。只在确认
Fiji.app/jars/ij-1.54p.jar 的 SHA-256 为
2E1A09961DFB41CEE66DDC821B2577A41A072566CE45A49BAE69267099741E20
后，使用 Release 中的补丁包和 tools/apply_patch.py 安装。先保留原 JAR
备份；哈希不匹配时停止，不要强行覆盖。安装后启动 Fiji，验证两个源图像
可以同时保留各自的 XY、XZ、YZ Orthogonal Views，并报告备份路径、校验值
和验证结果。不要修改其他插件或删除原文件。
```

## Git

```bash
git clone https://github.com/gouwenct/fiji-multi-orthogonal-views.git
```

这是非官方 Fiji 修改版。完整 Fiji 的原许可证和第三方声明保留在发行包中。
