package org.ocean.admin.gis.progress;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** 实时事件断线、提交竞态及页面刷新的统一补读入口。 */
@RestController
@RequestMapping("/api/gis/progress")
@RequiredArgsConstructor
@Tag(name = "GIS实时进度")
public class ProgressController {
    private final RealtimeProgressService progressService;

    @GetMapping("/{progressId}")
    @Operation(summary = "补查长耗时流程的完整进度快照",
            description = "用于首次登记、SSE重连和页面恢复时按已知progressId补查；日常变化由SSE推送，"
                    + "无需周期轮询。本接口不发现未知的进度编号，也不重新执行接入任务。")
    public ResponseResult<ProgressSnapshot> get(@PathVariable String progressId) {
        return ResponseResult.success(progressService.query(progressId));
    }
}
