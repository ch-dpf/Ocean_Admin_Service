package org.ocean.admin.gis.dto;

import jakarta.validation.constraints.NotNull;

/** 创建静态瓦片集归档的请求。 */
public record GisCreateTileSetArchiveRequest(
        @NotNull(message = "处理任务ID不能为空") Long processingTaskId) {
}
