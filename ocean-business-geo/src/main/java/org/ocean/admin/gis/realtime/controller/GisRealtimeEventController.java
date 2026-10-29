package org.ocean.admin.gis.realtime.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.kernel.common.ResponseResult;
import org.ocean.admin.gis.realtime.service.GisRealtimeSsePublisher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** GIS实时事件入口，仅订阅gis域主题。 */
@RestController
@RequiredArgsConstructor
@CrossOrigin(originPatterns = "*")
public class GisRealtimeEventController {
    public static final String STREAM_PATH = "/api/gis/events/stream";
    private final GisRealtimeSsePublisher publisher;

    @GetMapping(value = STREAM_PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅GIS实时事件", tags = "GIS实时事件",
            description = "建立SSE长连接，仅推送gis域事件。事件名为app.event，data为完整EventMessage JSON。"
                    + "省略topics订阅全部GIS事件，跨域主题返回400。通用进度事件为gis.progress.updated，"
                    + "上传与工作区接入均使用统一gis.progress事件。无历史重放；重连后使用任务响应中的progress.queryUrl补查。"
                    + "请使用EventSource或支持SSE的客户端持续接收事件。")
    @ApiResponse(responseCode = "200", description = "GIS事件流，上传批次通过recordNo关联，其余进度通过progressId关联",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "主题格式无效或不属于gis事件域",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ResponseResult.class)))
    @ApiResponse(responseCode = "503", description = "连接已满或服务正在关闭",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = ResponseResult.class)))
    public SseEmitter gisStream(
            @Parameter(description = "可选的gis域主题，逗号分隔，最多16项、总长512字符", example = "gis.progress")
            @RequestParam(required = false) String topics,
            HttpServletRequest request, HttpServletResponse response) {
        SseEmitter emitter = publisher.connect(topics);
        request.setAttribute(GisRealtimeSsePublisher.REQUEST_EMITTER_ATTRIBUTE, emitter);
        response.setContentType("text/event-stream;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store, no-transform");
        response.setHeader("X-Accel-Buffering", "no");
        return emitter;
    }
}
