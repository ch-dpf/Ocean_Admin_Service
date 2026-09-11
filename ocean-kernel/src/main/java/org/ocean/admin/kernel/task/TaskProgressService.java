package org.ocean.admin.kernel.task;

/**
 * 跨业务模块的后台任务进度契约。
 *
 * <p>kernel 只定义能力，不关心进度存储在内存、Redis，或通过何种协议推送。</p>
 */
public interface TaskProgressService {

    /** 使用业务侧稳定任务编号注册进度任务。 */
    String registerTask(String taskId, String taskName, int totalCount, String taskType);

    /** 上报一个工作项的处理结果。 */
    void updateProgress(String taskId, boolean success);

    /** 上报任务阶段及手工百分比。 */
    void updateProgress(String taskId, int progress, String stage, String message);

    /** 根据已经累计的成功、失败数量结束任务。 */
    void finalizeTaskResult(String taskId, String message);

    /** 将任务标记为整体失败。 */
    void finalizeTaskFailure(String taskId, String message);
}
