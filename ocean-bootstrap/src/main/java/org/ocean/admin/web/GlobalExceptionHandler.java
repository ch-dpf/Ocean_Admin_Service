package org.ocean.admin.web;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 将控制层异常统一转换为标准响应，避免落入 Spring Boot 默认错误模型。 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ResponseResult<Void>> handleResponseStatus(ResponseStatusException exception) {
        String message = hasText(exception.getReason())
                ? exception.getReason()
                : statusMessage(exception.getStatusCode());
        return error(exception.getStatusCode(), message);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResponseResult<Void>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(GlobalExceptionHandler::hasText)
                .findFirst()
                .orElse("请求参数校验失败");
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ResponseResult<Void>> handleHandlerMethodValidation(
            HandlerMethodValidationException exception) {
        String message = exception.getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .filter(GlobalExceptionHandler::hasText)
                .findFirst()
                .orElse("请求参数校验失败");
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ResponseResult<Void>> handleConstraintViolation(
            ConstraintViolationException exception) {
        String message = exception.getConstraintViolations().stream()
                .map(violation -> violation.getMessage())
                .filter(GlobalExceptionHandler::hasText)
                .findFirst()
                .orElse("请求参数校验失败");
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ResponseResult<Void>> handleInvalidRequest(Exception exception) {
        String message = switch (exception) {
            case MissingServletRequestParameterException missing ->
                    "缺少请求参数: " + missing.getParameterName();
            case MethodArgumentTypeMismatchException mismatch ->
                    "请求参数格式错误: " + mismatch.getName();
            default -> "请求体格式错误或无法读取";
        };
        return error(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResponseResult<Void>> handleIllegalArgument(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, messageOrDefault(exception, "请求参数错误"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ResponseResult<Void>> handleIllegalState(IllegalStateException exception) {
        return error(HttpStatus.CONFLICT, messageOrDefault(exception, "当前状态不允许执行该操作"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ResponseResult<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持: " + exception.getMethod());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ResponseResult<Void>> handleNoResourceFound(NoResourceFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "请求资源不存在");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseResult<Void>> handleUnexpected(Exception exception) {
        log.error("未处理的接口异常", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "系统内部错误，请稍后重试");
    }

    private ResponseEntity<ResponseResult<Void>> error(HttpStatusCode status, String message) {
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .body(ResponseResult.error(status.value(), message));
    }

    private static String messageOrDefault(Exception exception, String defaultMessage) {
        return hasText(exception.getMessage()) ? exception.getMessage() : defaultMessage;
    }

    private static String statusMessage(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null ? "请求处理失败" : resolved.getReasonPhrase();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
