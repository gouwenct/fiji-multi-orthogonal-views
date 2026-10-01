# Patch v0.1.0

- 目标：Fiji/ImageJ `ij-1.54p.jar`
- 修改类：`ij/plugin/Orthogonal_Views.class`
- 基础 JAR SHA-256：`2E1A09961DFB41CEE66DDC821B2577A41A072566CE45A49BAE69267099741E20`
- 修改后 JAR SHA-256：`15C18090A560E7BE334445F6E4F3335EFC0FCD1B491F8325BA8402562C95FEAF`
- Java：8 或更高版本运行 Fiji；应用补丁不需要 JDK。

补丁包包含修改后的类、源码和 `tools/apply_patch.py`。脚本会检查基础哈希，避免覆盖不兼容版本。
