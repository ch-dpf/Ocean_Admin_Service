package org.ocean.admin.platform.workbench.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.kernel.common.ResponseResult;
import org.ocean.admin.platform.workbench.service.SystemMetricsSsePublisher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 服务器资源指标监控入口，沿用platform域主题订阅规则。 */
@RestController
@RequestMapping("/api/platform/monitor")
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*")
public class MonitorController {
    public static final String METRICS_STREAM_PATH = "/api/platform/monitor/metrics";
    private final SystemMetricsSsePublisher publisher;

    @GetMapping(value = "/metrics", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅服务器资源指标", tags = "服务器资源监控",
            description = "建立SSE长连接，实时推送服务器CPU、GPU、内存和磁盘指标。事件名为app.event，data为完整EventMessage JSON。"
                    + "省略topics订阅全部平台事件，跨域主题返回400。空闲时发送注释心跳，不提供历史重放。"
                    + "请使用EventSource或支持SSE的客户端持续接收事件。")
    @ApiResponse(responseCode = "200", description = "服务器资源指标事件流，连接保持打开",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                    schema = @Schema(type = "string"), examples = @ExampleObject(value =
                    "event:app.event\ndata:{\"schemaVersion\":1,\"messageId\":\"example-id\","
                            + "\"type\":\"platform.metrics.updated\",\"source\":\"ocean.platform\","
                            + "\"subject\":\"system/metrics\",\"occurredAt\":\"2026-10-28T00:00:00Z\","
                            + "\"correlationId\":null,\"data\":{\"cpuUsage\":12.5}}\n\n")))
    @ApiResponse(responseCode = "400", description = "主题格式无效或不属于platform事件域",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ResponseResult.class)))
    @ApiResponse(responseCode = "503", description = "连接已满或服务正在关闭",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ResponseResult.class)))
    public SseEmitter metrics(
            @Parameter(description = "可选的platform域主题，逗号分隔，最多16项、总长512字符", example = "platform.metrics")
            @RequestParam(required = false) String topics,
            HttpServletRequest request, HttpServletResponse response) {
        SseEmitter emitter = publisher.connect(topics);
        request.setAttribute(SystemMetricsSsePublisher.REQUEST_EMITTER_ATTRIBUTE, emitter);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return emitter;
    }
}
