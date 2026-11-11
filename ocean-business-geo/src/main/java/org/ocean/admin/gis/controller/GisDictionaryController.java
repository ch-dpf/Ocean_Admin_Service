package org.ocean.admin.gis.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.service.GisDictionaryService;
import org.ocean.admin.kernel.common.ResponseResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** GIS 业务选择项只读接口，复用应用现有的登录校验。 */
@RestController
@RequestMapping("/api/gis/dictionaries")
@RequiredArgsConstructor
@Tag(name = "GIS 业务字典")
public class GisDictionaryController {
    private final GisDictionaryService dictionaries;

    @GetMapping("/data-management")
    @Operation(summary = "查询数据管理选择项", description = "按展示顺序返回，JSON 配置在服务重启后生效")
    public ResponseResult<Map<String, List<GisDictionaryService.Item>>> dataManagement() {
        return ResponseResult.success(dictionaries.all());
    }
}
