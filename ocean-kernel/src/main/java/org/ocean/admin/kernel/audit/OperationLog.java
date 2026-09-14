package org.ocean.admin.kernel.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个需要记录操作日志的业务入口。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OperationLog {

    /** 稳定的模块代码，用于检索和聚合。 */
    String module();

    /** 操作类型。 */
    OperationType type();

    /** 面向管理人员的操作描述。 */
    String description();

    /** 是否记录脱敏后的请求参数。 */
    boolean recordRequest() default true;

    /** 是否记录脱敏后的响应结果。 */
    boolean recordResponse() default false;

    /** 本操作额外需要脱敏的字段名。 */
    String[] excludeFields() default {};

    /** 是否异步投递日志。 */
    boolean async() default true;
}
