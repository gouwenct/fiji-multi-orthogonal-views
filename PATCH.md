# Orthogonal Views 补丁说明

上级索引：[README.md](README.md)。

- 目标：Fiji/ImageJ `ij-1.54p.jar`
- 修改类：`ij/plugin/Orthogonal_Views.class`、`ij/plugin/DragAndDrop.class`
- 基础 JAR SHA-256：`2E1A09961DFB41CEE66DDC821B2577A41A072566CE45A49BAE69267099741E20`
- 已发布 v0.1.0 的 JAR SHA-256：`15C18090A560E7BE334445F6E4F3335EFC0FCD1B491F8325BA8402562C95FEAF`
- v0.2.0 JAR SHA-256：`2F880B1489130FEF7DCC085C2FC5E39A06A74F02062350CF692B1EBAD81CD9F9`
- 当前补丁类 SHA-256：`506DEFF642A2AFDCA8C9DF62D6D738EA0938E7258A15ACA83CE4C887F3B77979`
- 当前拖放类 SHA-256：`27985876EC48BD401B637A6EE6B21F9F9329AEFA59BB6C95249238A05026D0EB`
- Java：8 或更高版本运行 Fiji；应用补丁不需要 JDK。
- 同步插件：已验证 `Sync_Win-1.7-fiji4.jar`，完整包内置此版本。

补丁包包含修改后的类、完整 Snapshot 脚本、源码、测试和 `tools/apply_patch.py`。

## 安装与升级

1. 保存图像并关闭 Fiji。
2. 解压补丁包，在其目录运行 `python tools/apply_patch.py "D:\Path\to\Fiji.app\jars\ij-1.54p.jar"`。
3. 重新启动 Fiji。同步操作见 [README.md](README.md#多图-orthogonal-views-同步)，快照操作见 [SNAPSHOT.md](SNAPSHOT.md)。

安装器要求 Python 3.8 或更高版本，并检查上面列出的原版或 v0.1.0 JAR 哈希。其他版本停止安装。更新内容是 JAR 内的两个类和 `scripts/Plugins/Snapshot_.groovy`；旧文件分别保留为同目录的 `.before-v0.2.0` 备份。原来没有 Snapshot 脚本时会新建脚本，不产生脚本备份。替换失败时恢复脚本，可在解除文件占用后重试。

如需回退，关闭 Fiji，将两个备份恢复为原文件名；原来没有脚本时移除本次安装的脚本。安装器测试见 [ApplyPatchTest.py](tests/ApplyPatchTest.py)，覆盖两个受支持版本、备份、未知哈希拒绝、Windows 文件占用时的回滚与重试。

## 鼠标同步修复（v0.2.0）

Sync Windows 会把源图像的 StackWindow 替换为 OpenStackWindow，并异步发出旧窗口的关闭通知。先开正交视图时，旧实现把该通知当成图像关闭，导致正交视图停止。现在会把窗口监听器绑定到替换后的源窗口。

关闭 Sync Windows 后，Java 的窗口列表仍可能包含已关闭的控制窗口。旧实现读取第一个匹配窗口就返回，重新打开后可能读到空的旧选择。现在只读取仍打开的控制窗口，并要求源图像和目标图像属于同一选择列表。同步遵守 `Sync Cursor` 和 `Sync z-Slices`，只从交互操作发布坐标，图像刷新只重画视图。

回归测试：[OrthogonalMouseSyncTest.java](tests/OrthogonalMouseSyncTest.java)。它通过真实画布派发鼠标事件，并通过 Sync Windows 列表选择 A/B/C，不直接写入插件的选择向量或调用 `setCrossLoc`。覆盖 `sync-first`、`ortho-first`、`reopen` 三种顺序、XY/XZ/YZ 单击与拖动、旋转 YZ、视图重画、开关、C/T 保持、未选中的 D、关闭源图像和控制窗口。

旧 JAR 已复现 `ortho-first` 和 `reopen` 失败；修复后均通过。另运行 [CloseAllOrthogonalTest.java](tests/CloseAllOrthogonalTest.java)，确认 `Ctrl+Shift+W` 一次关闭全部正交窗口仍通过。

编译时使用 Fiji 自带 Java 8 和 `plugins/jars/Compiler.jar` 中的 `com.sun.tools.javac.Main`。鼠标测试运行时将测试类目录、`jars/*`、`plugins/*`、`plugins/jars/*` 加入 classpath，主类为 `OrthogonalMouseSyncTest`，参数为上述三种顺序之一；必须在桌面环境运行。

实验 Fiji 的更新前 JAR 保存在同目录 `ij-1.54p.jar.before-sync-mousefix-20261026`。正在运行的 Fiji 会锁定 JAR，需先保存图像并退出再替换；替换后重新启动才能加载新类。Snapshot 兼容脚本独立保留，说明见 [SNAPSHOT.md](SNAPSHOT.md)。

## Snapshot ZIP 拖放（v0.2.0）

`ij/plugin/DragAndDrop.class` 已加入补丁。把包含根目录 `snapshot.json` 且 `format` 为 `fiji-snapshot` 的 ZIP 拖到 ImageJ 主窗口、工具栏或状态栏后，会直接调用 Snapshot 恢复流程，不弹出普通 ZIP 打开方式或文件选择框。v1/v2 Snapshot 均支持；普通 ZIP、TIFF 和其他文件继续走 ImageJ 原有打开逻辑。

拖放恢复不覆盖输入 ZIP。恢复失败写入 ImageJ 异常处理器；恢复成功只更新状态栏。当前本地实验 JAR 已包含该类，备份为 `ij-1.54p.jar.before-snapshot-drop-20261026`。

回归测试：[SnapshotDropTest.groovy](tests/SnapshotDropTest.groovy)。已验证 v1/v2 夹具、ROI/显示范围兼容流程、普通 ZIP 不误判、普通 TIFF 保持原打开行为，以及用户目录中的 `50-final.zip`：拖放恢复 10 张 TIFF，尺寸 512×512×32，初始 Z=17。

## 同步拖动性能（v0.2.0）

拖动操作与后台正交视图刷新均限制为最多约 30 次/秒。后台合并连续更新请求，并保留绘制过程中收到的请求；松开鼠标时立即提交最终坐标。每次绘制使用交叉点副本，避免绘制期间的新拖动改变正在计算的截面。图像数据和像素精度不变。

测试：[OrthogonalRefreshTest.java](tests/OrthogonalRefreshTest.java)。使用三组 512×512×32、32 位浮点堆栈，Z 像素间距为 XY 的 2 倍。通过真实画布每 8 ms 派发一次事件，共 240 次。以下为同一电脑上的一次前后对比；刷新次数是三组的合计，CPU 时间是整个测试进程的累计 CPU 时间，不是用户等待时间。

| 操作 | 优化前刷新次数 | 优化后刷新次数 | 优化前 CPU 时间 | 优化后 CPU 时间 |
| --- | ---: | ---: | ---: | ---: |
| XY 拖动 | 723 | 117 | 3203 ms | 1984 ms |
| 固定 X/Y，仅沿 Z 拖动 | 980 | 170 | 2609 ms | 1516 ms |

本次测试减少约 83% 的重复刷新，CPU 时间减少约 38%–42%。两种操作的事件输入均持续约 1.92 秒，包含等待最终状态和采样收尾的总时间约 2.0 秒；这些数据不能解释为端到端操作加速倍数，也不保证其他图像数量、尺寸或虚拟堆栈具有相同改善。

测试检查最终截面标题、实际像素和 Z 坐标。`race` 模式在绘制过程中插入新坐标，旧版会停留在旧截面，修订版能完成最后一次刷新。原鼠标同步的三种打开顺序、旋转 YZ、同步开关、C/T 保持和 Close All 回归均通过。补丁工具从基础 JAR 生成的结果与本次候选 JAR 哈希一致，仅正交视图类相对安装中的上一版改变，Snapshot 拖放类保持一致。

安装状态：已安装到实验 Fiji 的 `jars/ij-1.54p.jar`，安装后哈希与候选一致，最终位置和 Close All 回归通过。旧版备份为同目录的 `ij-1.54p.jar.before-sync-performance-20261026`。重新启动实验 Fiji 后生效，沿用现有同步操作。
