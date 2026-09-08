package org.ocean.admin.kernel.error;

/**
 * 跨模块共享的稳定错误契约。
 * {@link #code()} 面向程序判断，{@link #message()} 提供默认可读说明。
 */
public interface ErrorCode {
    /** 返回不会随展示文案变化的错误编码。 */
    String code();

    /** 返回错误的默认说明。 */
    String message();
}
