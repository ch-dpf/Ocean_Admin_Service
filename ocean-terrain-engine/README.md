# 地形引擎源码模块

本模块在调用方 JVM 内执行，无 Spring、数据库或进程启动依赖。GIS 业务通过 `org.ocean.terrain.TerrainTilingService` 调用；服务实例可共享，每次调用创建独立上下文。调用方提供 Jackson 3 ObjectMapper，并负责异步调度。任务中断不清除线程中断标记。

## 来源与许可

- 上游：https://github.com/Gaia3D/mago-3d-terrainer
- 基线：`v1.14.2-release`
- 提交：`d122bafcf9abca1dc4ad3cdf14dbfb8d84d44a92`
- 源码归档 SHA-256：`2dba3ae9f65363e5bcac43542ece8f3d67e8789802d5cbb4887da0a2b19b48f3`
- 许可：MPL-2.0，完整文本见 `LICENSE`。`com.gaia3d` 下的代码来自上游，已在本库修改；保留原包名以便追溯。分发本模块时须按许可证提供受覆盖源码及修改。
- `geoid/*.tif` 保留自该版本上游资源，使用 Git LFS 管理。

## 本库改造

按实际依赖裁剪 terrain、common、io 核心，删除 CLI 和全局日志配置；移除 GlobalOptions 单例，所有运行参数、缓存与文件资源通过 TerrainTilingContext 显式传递。地球 EPSG:4326/TMS 为当前业务支持范围，纯坐标计算使用固定地球椭球参数。日志接入 SLF4J，JSON 使用调用方提供的 Jackson 3 实例。构建使用父 POM 的 Java 21、GeoTools 和依赖收敛约束。

TerrainerPipeline 编排公共输入分析及栅格预处理，TerrainTileGenerator 定义格式生成阶段；当前实现 TerrainTilesetGenerator 生成 quantized-mesh，未来 heightmap 可复用前置流程。任务状态不存放在线程局部变量。GeoTIFF 使用明确持有的文件流，成功、失败和取消都关闭资源；清理只涉及本任务独占的临时目录。

最终 layer.json 保留输入 bounds，按实际落盘文件形成 available 范围，保留稀疏层级和空洞。继续处理会重算已有最深层以补齐被取消的层级。进度直接回调，瓦片写入关闭后计数；未知总量不推算百分比。任务日志记录阶段和完成量，详细算法日志进入主服务日志。

## 运行限制

同 JVM 共享堆空间；并发准入默认由 GIS 配置为 1，栅格缓存预算只约束缓存策略，不是堆硬隔离。超时包含准入等待，通过计算块检查和线程中断协作取消；不可中断的底层读取需等返回后释放资源。输出目录文件锁防止并发写入同一成果；锁文件保留供后续调用复用。当前仅提供 quantized-mesh，尚无 heightmap 输出。

构建：`mvn -pl ocean-terrain-engine -am package`；主服务构建会自动包含本模块。EGM 资源须先通过 `git lfs pull` 获取。

## 本次验证

在 Windows / Java 21 环境执行 11 项临时测试：基线瓦片与固定版本原 JAR 逐字节对比、调用线程验证、并发参数隔离、取消与资源回收、超时、非法输入、第二层中断续作、输出互斥、多输入与裁切 bounds、EGM96、EPSG:3857 重投影与全 NoData，以及 Spring 启用/禁用装配。所有测试通过；临时测试按仓库要求移除。另使用打包后主应用的 PropertiesLauncher 加载实际嵌套引擎 JAR，完成 EGM96 切片并验证没有子进程。未进行大规模 DEM 压力测试。

修正了原实现中读取流未关闭、续作漏补最深层、显式 NoData 未参与空块判定的问题；重投影失败会使任务失败，避免把原投影数据当作地理坐标继续输出。原 CLI 的独立 JSON 重建和行星投影写出回退未纳入本模块。
