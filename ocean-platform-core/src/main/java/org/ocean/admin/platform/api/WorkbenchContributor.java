package org.ocean.admin.platform.api;

import java.util.List;
import java.util.Map;

/**
 * 业务模块向统一工作台贡献卡片的扩展契约。
 * 实现方按所属平台返回只读展示数据；未实现此接口的模块不会出现在聚合结果中。
 */
public interface WorkbenchContributor {
    /** 返回贡献内容所属的平台编码。 */
    String platformCode();

    /** 返回可由工作台渲染的卡片数据。 */
    List<Map<String, Object>> cards();
}
