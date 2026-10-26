# Snapshot 使用说明

上级索引：[README.md](README.md)。

本说明适用于 Snapshot 独立插件和 v0.2.0 Fiji 中的 `Plugins > Snapshot`。快照文件是 `.zip`，用于保存一批图像及其显示状态。它不是单张 TIFF 的替代格式。

v0.2.0 支持拖放：把包含根目录 `snapshot.json` 的 Snapshot `.zip` 拖到 ImageJ 主窗口、工具栏或状态栏，会直接恢复整批图像。普通 ZIP 不会被误认为 Snapshot；拖放入口支持 v1 和 v2。

当前约定固定使用 ZIP 根目录的 `snapshot.json` 作为识别标记和恢复清单。用户不需要手动打开或编辑它；后续批量代码只需按本文档生成该文件和对应的 TIFF 资源即可。

完整包保留了两个同名入口：简易的“保存快照 / 恢复快照”对话框来自 `scripts/Plugins/Snapshot_.groovy`，常驻面板来自 `plugins/Snapshot.jar`。两者的文件格式不同。简易入口支持 v1 和 v2 读取；常驻面板使用 v2。补丁包安装简易入口，常驻面板是否存在取决于原 Fiji 环境。

## 独立插件安装

下载 [Snapshot 独立插件包](https://github.com/gouwenct/fiji-multi-orthogonal-views/releases/download/snapshot-v0.1.0/Snapshot-standalone-v0.1.0.zip)，或直接下载 [Snapshot.jar](https://github.com/gouwenct/fiji-multi-orthogonal-views/releases/download/snapshot-v0.1.0/Snapshot.jar)。发行页提供 `SNAPSHOT-SHA256SUMS.txt` 校验值。

1. 保存图像并关闭 Fiji。
2. 若已有 `Snapshot.jar` 或 `Snapshot2.jar`，先把旧文件移到 Fiji 目录外备份。两个名称对应的当前 JAR 内容相同，保留一份即可。
3. 将下载的 `Snapshot.jar` 复制到 `Fiji.app/plugins/`。
4. 重新启动 Fiji，打开 `Plugins > Snapshot`，使用常驻面板保存、导入和恢复快照。

运行依赖为 ImageJ 1.x、Gson 和 Java 8 或更高版本；已验证 ImageJ 1.54p、Gson 2.10.1 和 Java 8。独立包不包含这些依赖库，适合安装到已有 Fiji 中。

独立 JAR 通过面板导入 v2 快照。把 ZIP 拖到 ImageJ 主窗口自动恢复，需要本项目 v0.2.0 完整包或 [核心补丁](PATCH.md#安装与升级)。独立包的 `src/Snapshot_.groovy` 是可选的 v1/v2 兼容脚本；需要简易入口时，将它复制到 `Fiji.app/scripts/Plugins/` 后重启。

## 保存一批图像

1. 用 Fiji 打开要查看的 TIFF。每个图像窗口都会出现在 Snapshot 的图像列表中。
2. 打开 `Plugins > Snapshot`，进入保存快照操作。
3. 常驻面板可以选择图像；简易入口会收集当前全部图像窗口。填写目标 `.zip` 文件名。
4. 选择图像保存方式：
   - **全部嵌入 TIFF**：把像素数据复制进快照。适合归档、跨电脑查看，文件较大。
   - **自动混合**：有可读取原始 TIFF 且没有未保存修改的图像使用外部路径；其他图像嵌入快照。适合本机快速保存。
   - **全部使用外部路径**：只记录 TIFF 的绝对路径。文件最小，但移动数据或换电脑后需要修复路径。
5. 保存完成后得到一个 `.zip`。以后只需恢复这个文件即可重新打开整批图像。

上述三种方式对应常驻面板。简易入口提供“嵌入 TIFF”和“仅记录 TIFF 路径”；选择路径模式时，对没有有效路径或存在未保存修改的窗口逐个确认。

快照还记录当前 C/Z/T 位置、通道显示范围、活动通道、窗口位置、缩放、视口和 ROI。保存前应先确认图像已经处于希望复查的状态。

## 恢复快照

1. 打开 `Plugins > Snapshot`。简易对话框选择“恢复快照”；常驻面板先“导入一个或多个 .zip”，再选中条目并“恢复选中”。
2. 选择要恢复的 `.zip`。简易入口支持 v1 和 v2，常驻面板使用 v2。
3. 嵌入 TIFF 会直接从快照打开。外部路径仍存在时会直接打开；路径失效时，在路径修复窗口中选择新的 TIFF。
4. 修复外部路径后，另存为新的 `.zip`。原快照不会被覆盖。

常驻面板的“排列”页可以选择图像并自动排列窗口，“日志”页记录打开来源和失败原因。简易入口把恢复记录写入 ImageJ 的 Log 窗口。

## 多个快照的集合

**Collection** 用于把至少两个已经存在的 Snapshot `.zip` 组织成一个集合。集合适合按实验批次管理多个快照；集合成员仍然是普通 Snapshot，恢复时可以选择成员。

如果目标只是一次打开一批新生成的 TIFF，优先生成一个包含全部 TIFF 的普通 Snapshot；不需要先为每个 TIFF 建立单独 Snapshot 再做 Collection。

## 后续代码生成 TIFF 结果时的约定

后续批处理代码应生成一个可直接由 Snapshot 恢复的 `.zip`，并把每个结果 TIFF 作为嵌入资源放入该快照。建议流程是：

1. 记录结果 TIFF 的稳定顺序、显示名称和 C/Z/T 初始位置。
2. 生成一个 `fiji-snapshot` manifest，并把每个结果对应到 `images/image-XXXX.tif`。
3. 将 manifest 写成 ZIP 根目录的 `snapshot.json`，同时写入 `images/` 下的 TIFF。
4. 固定 `format` 和 `formatVersion`，不要自行改变字段名；如果 Snapshot 插件升级，先用当前插件保存一个样例快照，再按样例生成。
5. 生成后在 Fiji 中用 `Plugins > Snapshot` 恢复一次，确认图像数量、顺序和尺寸一致。

新生成的快照使用 v2：`format` 为 `fiji-snapshot`，`formatVersion` 为数字 `2`。每张图像使用 `storageKind`（`EMBEDDED` 或 `ABSOLUTE_PATH`）、`storagePath`、`dimensions`、`position.channel/slice/frame` 和 `displayState`。C/Z/T 均从 1 开始。嵌入 TIFF 的路径相对于 ZIP 根目录。

简易入口保存的 v1 使用 `version: 1`、`storage.kind/path`、`position.c/z/t` 和 `display`。不要只替换版本号：图像字段也必须匹配对应版本。

嵌入模式是批量结果的默认选择。它不依赖原始 TIFF 的绝对路径，适合把结果目录交给其他人或移动到另一台电脑。批量代码暂时不要直接修改现有快照，也不要覆盖同名文件；先写入临时 ZIP，再原子替换目标文件。

## 文件边界

- Snapshot 保存的是图像、显示状态和 ROI；它不会保存 Fiji 的全部全局设置。
- 外部路径模式依赖原 TIFF 的绝对路径。路径修复后应另存新快照。
- 新快照统一使用 `.zip`。`.ijsnapshot` 只在简易入口作为旧文件名兼容，常驻面板要求 `.zip`。

## v2 兼容修复与验证

2026-10-26：修复简易入口读取 v2 时的 `Unsupported snapshot version: null`。兼容层只转换内存中的 manifest，保留 TIFF、C/Z/T、显示范围、窗口、缩放和 ROI；不修改输入快照。另存路径修复结果时写成完整 v1 格式。

完整脚本为 [Snapshot_.groovy](src/Snapshot_.groovy)，v2 兼容与拖放差异记录在 [Snapshot_v2_compat.patch](patch/Snapshot_v2_compat.patch)。v0.2.0 完整包已集成修复，补丁安装器会部署到 `scripts/Plugins/Snapshot_.groovy`，并备份原有脚本。

回归检查：[SnapshotCompatibilityTest.groovy](tests/SnapshotCompatibilityTest.groovy)。从完整包根目录运行（`Fiji.app` 与 `tests` 在同一目录）：

```powershell
& '.\Fiji.app\java\win64\zulu8.60.0.21-ca-fx-jdk8.0.322-win_x64\jre\bin\java.exe' `
  '-Dfile.encoding=UTF-8' '-Djava.awt.headless=true' -Xmx3g `
  -cp '.\Fiji.app\jars\*' groovy.ui.GroovyMain `
  '.\tests\SnapshotCompatibilityTest.groovy' `
  '.\Fiji.app\scripts\Plugins\Snapshot_.groovy' `
  'D:\data\snapshots'
```

`D:\data\snapshots` 为示例数据目录，可省略最后一个参数，只测试生成的夹具。此前还验证了 5 个实际 ZIP，共 35 张图像：实际恢复后，尺寸、C/Z/T、显示范围与 manifest 一致；输入 ZIP 的 SHA-256 未变。测试还覆盖 v1、v2、ROI、路径修复后另存和拒绝未知版本。

## 独立面板 JAR 与源码

仓库中的 [plugins/Snapshot.jar](plugins/Snapshot.jar) 是常驻面板编译包，与 Fiji v0.2.0 完整包中的 JAR 相同。独立发行标签为 `snapshot-v0.1.0`；JAR 内部 Maven 版本仍为 `0.1.0-SNAPSHOT`，本次分发没有重新编译或修改面板功能。

JAR SHA-256：`3E88F67DF3558AA226533BE93A3B4848F1E97988DAD49833D3B56149D4EDB02B`。

常驻面板的完整 Java 工程源码目前不在此仓库。[src/Snapshot_.groovy](src/Snapshot_.groovy) 是简易入口的独立实现，不能用于重新编译面板 JAR。

回归测试：[SnapshotPluginTest.groovy](tests/SnapshotPluginTest.groovy)。使用未打核心补丁的 ImageJ 1.54p，验证插件入口类加载、两张嵌入 TIFF 的保存与恢复、全部像素、Z 位置、显示范围和 ROI，以及兼容脚本恢复插件生成的 v2 快照；输入 ZIP 保持不变。测试在 Windows、Java 8 和 Gson 2.10.1 环境通过，未验证其他操作系统。
