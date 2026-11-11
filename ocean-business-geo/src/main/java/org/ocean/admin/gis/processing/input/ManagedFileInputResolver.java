package org.ocean.admin.gis.processing.input;

import lombok.RequiredArgsConstructor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.ocean.admin.gis.mapper.GisFileGroupMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.processing.config.GisProcessingProperties;
import org.ocean.admin.gis.entity.GisDataSet;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.entity.GisFileFormat;
import org.ocean.admin.gis.entity.GisFileGroup;
import org.ocean.admin.gis.entity.GisProcessingInput;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.processing.GisInputSourceType;
import org.ocean.admin.gis.processing.GisSubmitProcessingCommand;
import org.ocean.admin.gis.service.FileMetaService;
import org.ocean.admin.gis.service.FileGroupService;
import org.ocean.admin.gis.service.GdalDatasetReader;
import org.ocean.admin.gis.progress.ProgressStage;
import org.ocean.admin.gis.progress.ProgressUnit;
import org.ocean.admin.gis.processing.GisProcessingType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Consumer;

/** 从文件元数据管理领域读取已入库文件。 */
@Component
@RequiredArgsConstructor
public class ManagedFileInputResolver implements GisProcessingInputResolver {
    private final FileMetaService fileMetaService;
    private final GisDataSetMapper dataSetMapper;
    private final FileGroupService fileGroups;
    private final GdalDatasetReader reader;
    private final ObjectMapper json;
    private final GisFileGroupMapper groupMapper;
    private final GisFileMetaMapper metaMapper;
    private final GisProcessingProperties limits;

    /** 任务独占的来源清单；执行时只能读取该清单，不以当前组成员替换历史输入。 */
    public record GroupSnapshot(GisFileGroup group, List<GisFileMeta> members) {
        public GroupSnapshot {
            Objects.requireNonNull(group, "处理输入缺少文件组快照");
            members = List.copyOf(members);
            if (group.getId() == null || group.getDataSetId() == null || group.getInputRevision() == null
                    || group.getInputRevision() < 0 || group.getEntryFileId() == null || group.getReadOptionsJson() == null
                    || group.getFormat() == null || members.isEmpty())
                throw new IllegalArgumentException("处理输入文件组快照不完整");
            var ids = new LinkedHashSet<Long>();
            for (GisFileMeta member : members) {
                if (member.getId() == null || !ids.add(member.getId()) || !group.getId().equals(member.getFileGroupId())
                        || !Objects.equals(group.getDataSetId(), member.getDataSetId()) || member.getMemberPath() == null
                        || member.getMemberPath().isBlank() || member.getSizeBytes() == null || member.getSizeBytes() < 0
                        || member.getSha256() == null
                        || !member.getSha256().matches("[a-fA-F0-9]{64}")) {
                    throw new IllegalArgumentException("处理输入成员快照缺少唯一身份、组内路径或内容摘要");
                }
                boolean uploaded = "UPLOAD".equals(member.getSourceType()) && "LOCAL".equals(member.getStorageType())
                        && member.getStorageKey() != null && !member.getStorageKey().isBlank();
                boolean workspace = "WORKSPACE".equals(member.getSourceType()) && member.getWorkspaceCode() != null
                        && !member.getWorkspaceCode().isBlank() && member.getRelativePath() != null
                        && !member.getRelativePath().isBlank() && member.getSourceModifiedAt() != null;
                if (!uploaded && !workspace) throw new IllegalArgumentException("处理输入成员快照缺少完整受控来源定位");
            }
            if (!ids.contains(group.getEntryFileId())) throw new IllegalArgumentException("处理输入快照缺少组入口");
        }
    }

    @Override
    public GisInputSourceType type() {
        return GisInputSourceType.MANAGED_FILE;
    }

    @Override
    public PreparedInputs prepare(GisSubmitProcessingCommand command, String taskNo) {
        List<GroupSnapshot> snapshots;
        if (command.dataSetId() != null) {
            if (command.fileMetaIds() != null) throw new IllegalArgumentException("数据集整体提交不能同时指定文件ID");
            snapshots = dataSetSnapshots(command);
        } else {
            List<Long> ids = command.fileMetaIds();
            if (ids == null || ids.isEmpty() || ids.size() > 100 || ids.stream().anyMatch(Objects::isNull)
                    || new LinkedHashSet<>(ids).size() != ids.size()) {
                throw new IllegalArgumentException("文件元数据 ID 必须为1到100个且不能重复或为空");
            }
            snapshots = new ArrayList<>(ids.size());
            for (Long id : ids) {
                GisFileMeta meta = getValidMeta(id, command);
                GisFileGroup group = fileGroups.getRequired(meta.getFileGroupId());
                if (!meta.getId().equals(group.getEntryFileId())) {
                    throw new IllegalStateException("只支持文件组的主入口: " + id);
                }
                snapshots.add(new GroupSnapshot(group, fileGroups.members(group.getId()).stream()
                        .sorted(Comparator.comparing(GisFileMeta::getId)).toList()));
            }
        }
        List<GisProcessingInput> inputs = new ArrayList<>(snapshots.size());
        Map<Long, GisDataSet> dataSets = new LinkedHashMap<>();
        for (int i = 0; i < snapshots.size(); i++) {
            GroupSnapshot snapshot = snapshots.get(i);
            GisFileGroup group = snapshot.group();
            GisFileMeta meta = snapshot.members().stream()
                    .filter(member -> member.getId().equals(group.getEntryFileId())).findFirst().orElseThrow();
            if (!"COMPLETE".equals(group.getAssemblyStatus())
                    || !"COMPLETED".equals(group.getParseStatus())) {
                throw new IllegalStateException("只支持已完整解析文件组的主入口: " + meta.getId());
            }
            if (!"COMPLETED".equals(meta.getMetadataStatus())) {
                throw new IllegalStateException("文件组入口尚未完成空间解析: " + meta.getId());
            }
            GisFileFormat format = GisFileFormat.valueOf(group.getFormat());
            if (!format.isRaster() || command.processingType() == GisProcessingType.VECTOR)
                throw new IllegalArgumentException("当前已管理文件切片仅支持影像或地形栅格文件组");
            GisDataSet dataSet = dataSets.computeIfAbsent(meta.getDataSetId(), id -> requireDataSet(meta));
            if (!command.processingType().categoryId().equals(dataSet.getCategoryId())) {
                throw new IllegalArgumentException("处理类型与文件所属数据集类别不匹配: " + meta.getId());
            }
            for (GisFileMeta member : snapshot.members()) {
                if (!"READY".equals(member.getFileStatus())) throw new IllegalStateException("文件组存在未就绪成员: " + member.getId());
                fileMetaService.resolveSourcePath(member);
            }
            GisProcessingInput input = new GisProcessingInput();
            input.setSequenceNo(i + 1);
            input.setInputKind("FILE");
            input.setInputStatus("READY");
            input.setFileMetaId(meta.getId());
            input.setSourceFileMetaId(meta.getId());
            input.setSourceFileGroupId(group.getId());
            input.setSourceGroupRevision(group.getInputRevision());
            input.setSourceGroupSnapshot(json.writeValueAsString(snapshot));
            input.setSourceDataSetId(meta.getDataSetId());
            input.setSourceDataSetName(dataSet.getDataSetName());
            input.setSourceStorageType(meta.getStorageType());
            input.setSourceStorageKey(meta.getStorageKey());
            input.setSourceLocatorType(meta.getSourceType());
            input.setSourceModifiedAt(meta.getSourceModifiedAt());
            input.setWorkspaceCode(meta.getWorkspaceCode());
            input.setRelativePath(meta.getRelativePath());
            input.setOriginalName(meta.getOriginalName());
            input.setExtension(meta.getExtension());
            input.setSizeBytes(meta.getSizeBytes());
            input.setSha256(meta.getSha256());
            inputs.add(input);
        }
        return new PreparedInputs(inputs, null);
    }

    /** 批量读取数据集，不能将未解析、未归组或不可用文件静默排除。 */
    private List<GroupSnapshot> dataSetSnapshots(GisSubmitProcessingCommand command) {
        List<GisFileGroup> groups = groupMapper.selectList(new LambdaQueryWrapper<GisFileGroup>()
                .eq(GisFileGroup::getDataSetId, command.dataSetId())
                .ne(GisFileGroup::getAssemblyStatus, "DUPLICATE")
                .isNull(GisFileGroup::getDuplicateOfGroupId)
                .orderByAsc(GisFileGroup::getId)
                .last("LIMIT " + (limits.getDataSetMaxInputGroups() + 1)));
        if (groups.isEmpty()) throw new IllegalArgumentException("数据集没有可处理的文件组");
        if (groups.size() > limits.getDataSetMaxInputGroups()) {
            throw new IllegalArgumentException("数据集文件组超过单任务上限" + limits.getDataSetMaxInputGroups()
                    + "，可调整gis.processing.data-set-max-input-groups");
        }
        Map<Long, List<GisFileMeta>> members = new LinkedHashMap<>();
        for (GisFileGroup group : groups) {
            if (!"COMPLETE".equals(group.getAssemblyStatus()) || !"COMPLETED".equals(group.getParseStatus())) {
                throw new IllegalStateException("数据集存在未完整解析的文件组: " + group.getId());
            }
            members.put(group.getId(), new ArrayList<>());
        }
        for (GisFileMeta file : metaMapper.selectDataSetProcessingFiles(command.dataSetId())) {
            List<GisFileMeta> groupMembers = members.get(file.getFileGroupId());
            if (groupMembers == null) {
                throw new IllegalStateException("数据集存在未归组文件或文件组已变化，请完成解析后重新提交: " + file.getId());
            }
            groupMembers.add(file);
        }
        return groups.stream().map(group -> new GroupSnapshot(group, members.get(group.getId()))).toList();
    }

    @Override
    public List<Path> resolveRuntime(List<GisProcessingInput> inputs) {
        return inputs.stream().map(input -> {
            GroupSnapshot snapshot = readSnapshot(input);
            return fileMetaService.resolveSourcePath(snapshot.members().stream()
                    .filter(member -> member.getId().equals(snapshot.group().getEntryFileId())).findFirst().orElseThrow());
        }).toList();
    }

    @Override
    public RuntimeInputs openRuntime(List<GisProcessingInput> inputs, Runnable heartbeat, Consumer<ProgressStage> progress) {
        List<GdalDatasetReader.ReadContext> contexts = new ArrayList<>();
        List<GroupSnapshot> snapshots = inputs.stream().map(this::readSnapshot).toList();
        List<Path> paths = new ArrayList<>();
        try {
            verifyManifest(snapshots, heartbeat, progress, "SOURCE_VERIFYING", "核验源文件");
            for (GroupSnapshot snapshot : snapshots) {
                var context = reader.prepare(snapshot.group(), snapshot.members(), heartbeat, progress);
                contexts.add(context);
                if (!context.vectors().isEmpty() || context.rasters().isEmpty())
                    throw new IllegalStateException("已管理文件组未产生可处理的标准栅格");
                paths.addAll(context.rasters().stream().map(GdalDatasetReader.RasterSource::geoTiffPath).toList());
            }
        } catch (RuntimeException ex) {
            try { closeContexts(contexts); } catch (RuntimeException cleanup) { ex.addSuppressed(cleanup); }
            throw ex;
        }
        return new RuntimeInputs(paths, () -> {
            progress.accept(ProgressStage.indeterminate("SOURCE_REVERIFYING", "切片完成，复核源文件"));
            for (var context : contexts) context.verifySources();
            verifyManifest(snapshots, heartbeat, progress, "SOURCE_REVERIFYING", "复核源文件");
        }, () -> closeContexts(contexts));
    }

    private GroupSnapshot readSnapshot(GisProcessingInput input) {
        if (input.getSourceGroupSnapshot() == null || input.getSourceFileGroupId() == null || input.getSourceGroupRevision() == null)
            throw new IllegalStateException("处理输入缺少完整文件组快照，请重新提交任务");
        GroupSnapshot snapshot = json.readValue(input.getSourceGroupSnapshot(), GroupSnapshot.class);
        if (!input.getSourceFileGroupId().equals(snapshot.group().getId())
                || !input.getSourceGroupRevision().equals(snapshot.group().getInputRevision())
                || !Objects.equals(input.getSourceFileMetaId(), snapshot.group().getEntryFileId())
                || !"COMPLETE".equals(snapshot.group().getAssemblyStatus()) || !"COMPLETED".equals(snapshot.group().getParseStatus())
                || !GisFileFormat.valueOf(snapshot.group().getFormat()).isRaster())
            throw new IllegalStateException("处理输入文件组快照身份或解析状态不一致");
        return snapshot;
    }

    /** 内容摘要在后台读取前后核验，提交请求只校验来源定位，不同步扫描大文件。 */
    private void verifyManifest(List<GroupSnapshot> snapshots, Runnable heartbeat,
            Consumer<ProgressStage> progress, String code, String message) {
        List<GisFileMeta> members = snapshots.stream().flatMap(snapshot -> snapshot.members().stream()).toList();
        long totalBytes = 0;
        for (GisFileMeta member : members) totalBytes = Math.addExact(totalBytes, member.getSizeBytes());
        Consumer<ProgressStage> reporter = ProgressStage.throttled(progress);
        reporter.accept(totalBytes == 0 ? ProgressStage.indeterminate(code, message)
                : ProgressStage.determinate(code, message, 0, totalBytes, ProgressUnit.BYTE));
        long verifiedBytes = 0;
        byte[] buffer = new byte[1024 * 1024];
        for (GisFileMeta member : members) {
            heartbeat.run();
            Path path = fileMetaService.resolveSourcePath(member);
            String detail = message + ": " + member.getMemberPath();
            try (InputStream input = Files.newInputStream(path)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                int count;
                long size = 0;
                while ((count = input.read(buffer)) != -1) {
                    heartbeat.run();
                    digest.update(buffer, 0, count);
                    size += count;
                    if (size > member.getSizeBytes())
                        throw new IllegalStateException("处理输入成员大小与提交快照不一致: " + member.getId());
                    // 摘要和来源定位验证通过前，不发布本阶段完成事件。
                    long completed = verifiedBytes + size;
                    if (completed < totalBytes)
                        reporter.accept(ProgressStage.determinate(code, detail, completed, totalBytes, ProgressUnit.BYTE));
                }
                if (!Objects.equals(member.getSizeBytes(), size)
                        || !member.getSha256().equalsIgnoreCase(HexFormat.of().formatHex(digest.digest())))
                    throw new IllegalStateException("处理输入成员内容与提交快照不一致: " + member.getId());
                fileMetaService.resolveSourcePath(member);
                verifiedBytes += size;
                if (verifiedBytes < totalBytes)
                    reporter.accept(ProgressStage.determinate(code, detail, verifiedBytes, totalBytes, ProgressUnit.BYTE));
            } catch (IllegalStateException ex) { throw ex; }
            catch (Exception ex) { throw new IllegalStateException("无法核验处理输入成员: " + member.getId(), ex); }
        }
        if (totalBytes > 0)
            reporter.accept(ProgressStage.determinate(code, message + "完成", totalBytes, totalBytes, ProgressUnit.BYTE));
    }

    private void closeContexts(List<GdalDatasetReader.ReadContext> contexts) {
        RuntimeException failure = null;
        for (var context : contexts.reversed()) {
            try { context.close(); }
            catch (RuntimeException ex) {
                if (failure == null) failure = ex;
                else failure.addSuppressed(ex);
            }
        }
        if (failure != null) throw failure;
    }

    private GisFileMeta getValidMeta(Long id, GisSubmitProcessingCommand command) {
        GisFileMeta meta = fileMetaService.getRequiredEntity(id);
        if (!"READY".equals(meta.getFileStatus()) || !"COMPLETED".equals(meta.getMetadataStatus())) {
            throw new IllegalStateException("只支持处理已就绪且完成空间元数据提取的文件: " + id);
        }
        if (meta.getFileGroupId() == null) throw new IllegalStateException("处理输入尚未归入已解析文件组: " + id);
        fileMetaService.resolveSourcePath(meta);
        GisDataSet dataSet = requireDataSet(meta);
        if (dataSet == null || !command.processingType().categoryId().equals(dataSet.getCategoryId())) {
            throw new IllegalArgumentException("处理类型与文件所属数据集类别不匹配: " + id);
        }
        return meta;
    }

    private GisDataSet requireDataSet(GisFileMeta meta) {
        GisDataSet dataSet = dataSetMapper.selectById(meta.getDataSetId());
        if (dataSet == null) {
            throw new IllegalArgumentException("文件所属数据集不存在: " + meta.getDataSetId());
        }
        return dataSet;
    }
}
