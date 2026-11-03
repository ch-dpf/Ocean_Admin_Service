package org.ocean.admin.gis.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.ocean.admin.gis.entity.GisFileFormat;
import org.ocean.admin.gis.entity.GisFileGroup;
import org.ocean.admin.gis.entity.GisFileMeta;
import org.ocean.admin.gis.mapper.GisDataSetMapper;
import org.ocean.admin.gis.mapper.GisFileGroupMapper;
import org.ocean.admin.gis.mapper.GisFileMetaMapper;
import org.ocean.admin.gis.mapper.GisProcessingInputMapper;
import org.ocean.admin.gis.mapper.GisVectorMapper;
import org.ocean.admin.gis.processing.input.ControlledWorkspaceResolver;
import org.ocean.admin.gis.util.FileUploadUtil;
import org.ocean.admin.gis.vo.GisFileGroupVO;
import org.ocean.admin.kernel.common.PageResult;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** 在同一数据集内识别独占成员、校验完整性并按内容建立唯一逻辑数据源。 */
@Service
@RequiredArgsConstructor
public class FileGroupService {
    private final GisFileGroupMapper groups;
    private final GisFileMetaMapper files;
    private final GisDataSetMapper dataSets;
    private final GisProcessingInputMapper processingInputs;
    private final GisVectorMapper vectors;
    private final ControlledWorkspaceResolver sources;
    private final FileUploadUtil uploads;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;

    /** 传输收口后才组装；数据集锁将不同实例的识别和指纹占用串行化。 */
    public List<Long> assembleIngestion(String progressId) {
        if (progressId == null || progressId.isBlank()) throw new IllegalArgumentException("接入进度不能为空");
        List<GisFileMeta> incoming = groups.ingestionFiles(progressId);
        Map<Long, SourceSnapshot> snapshots = prepareSources(incoming);
        List<Long> ready = new ArrayList<>();
        for (Long dataSetId : incoming.stream().map(GisFileMeta::getDataSetId).distinct().sorted().toList()) {
            List<Long> entries = transactions.execute(status -> assembleDataSet(dataSetId, progressId, snapshots));
            if (entries != null) ready.addAll(entries);
        }
        return ready;
    }

    private List<Long> assembleDataSet(Long dataSetId, String progressId, Map<Long, SourceSnapshot> snapshots) {
        if (dataSets.selectActiveByIdForUpdate(dataSetId) == null) throw new IllegalStateException("数据集已删除");
        List<GisFileGroup> existing = groups.lockByDataSet(dataSetId);
        List<GisFileMeta> incoming = groups.ingestionFiles(progressId).stream()
                .filter(file -> dataSetId.equals(file.getDataSetId()) && "READY".equals(file.getFileStatus())).toList();
        List<GisFileMeta> unassigned = incoming.stream().filter(file -> file.getFileGroupId() == null).toList();
        Map<String, List<GisFileMeta>> buckets = new LinkedHashMap<>();
        for (GisFileMeta file : unassigned) {
            try {
                applySnapshot(file, snapshots);
            } catch (RuntimeException ex) {
                String message = "无法验证输入来源，文件已保留：" + ex.getMessage();
                groups.park(file.getId(), sourceErrorCode(file, snapshots), message.substring(0, Math.min(message.length(), 1000)));
                continue;
            }
            buckets.computeIfAbsent(sourceBucket(file), key -> new ArrayList<>()).add(file);
        }
        List<Long> ready = new ArrayList<>();
        for (List<GisFileMeta> received : buckets.values()) {
            Map<String, GisFileMeta> unique = new LinkedHashMap<>();
            Map<GisFileMeta, List<GisFileMeta>> repeated = new LinkedHashMap<>();
            for (GisFileMeta file : received) {
                String digest = file.getSha256();
                String key = file.getOriginalName().toLowerCase(Locale.ROOT) + "\n" + (digest == null ? file.getId() : digest);
                GisFileMeta representative = unique.putIfAbsent(key, file);
                if (representative != null) repeated.computeIfAbsent(representative, ignored -> new ArrayList<>()).add(file);
            }
            List<GisFileMeta> bucket = new ArrayList<>(unique.values());
            List<GisFileGroup> touched = new ArrayList<>();
            List<GisFileMeta> entries = bucket.stream()
                    .filter(file -> GisFileFormat.fromFileName(file.getOriginalName()) != null && !isRasterTab(file, snapshots)).toList();
            for (GisFileMeta entry : entries) {
                GisFileFormat format = GisFileFormat.fromFileName(entry.getOriginalName());
                List<GisFileMeta> matched = new ArrayList<>();
                for (GisFileMeta member : bucket) {
                    if (member.getFileGroupId() != null || !format.acceptsMember(member.getOriginalName())) continue;
                    long possibleEntries = entries.stream().filter(candidate -> matchesEvidence(candidate, member, snapshots)).count();
                    if (possibleEntries == 1 && matchesEvidence(entry, member, snapshots)) matched.add(member);
                }
                if (!matched.contains(entry)) continue;
                if (hasDuplicateRoles(format, matched)) {
                    for (GisFileMeta file : matched) groups.park(file.getId(), "AMBIGUOUS_MEMBERS", "同名配套文件存在歧义，无法自动归组");
                    continue;
                }
                GisFileGroup group = findSupplement(existing, format, matched, snapshots);
                if (group == null) group = createGroup(dataSetId, format, entry.getOriginalName());
                attach(group, matched);
                touched.add(group);
                if (!existing.contains(group)) existing.add(group);
            }
            // 只有扩展名能唯一确认格式的残缺输入才建组；通用 prj 等保持未识别。
            for (GisFileMeta member : bucket) {
                if (member.getFileGroupId() != null) continue;
                List<GisFileGroup> candidates = existing.stream()
                        .filter(group -> canSupplement(group, member, snapshots)).toList();
                if (candidates.size() == 1) {
                    GisFileGroup group = candidates.getFirst();
                    attach(group, List.of(member));
                    if (!touched.contains(group)) touched.add(group);
                    continue;
                }
                List<GisFileFormat> formats = Arrays.stream(GisFileFormat.values())
                        .filter(format -> format.requiredExtensions().contains(GisFileFormat.extension(member.getOriginalName()))).toList();
                if (candidates.isEmpty() && formats.size() == 1) {
                    GisFileFormat format = formats.getFirst();
                    List<GisFileMeta> components = bucket.stream().filter(file -> file.getFileGroupId() == null
                            && sameStem(member.getOriginalName(), file.getOriginalName())
                            && format.acceptsMember(file.getOriginalName())).toList();
                    if (!hasDuplicateRoles(format, components)) {
                        GisFileGroup group = createGroup(dataSetId, format, stem(member.getOriginalName()));
                        attach(group, components);
                        existing.add(group);
                        touched.add(group);
                    }
                }
            }
            for (GisFileGroup group : touched) {
                if (reconcile(group, progressId, snapshots)) ready.add(group.getEntryFileId());
            }
            preserveDuplicateReceipts(dataSetId, progressId, repeated);
        }
        for (GisFileMeta file : unassigned) {
            if (file.getFileGroupId() == null && snapshots.get(file.getId()) != null && snapshots.get(file.getId()).errorCode() == null
                    && buckets.values().stream().anyMatch(bucket -> bucket.contains(file)))
                groups.park(file.getId(), "GROUP_UNRECOGNIZED", "缺少可唯一识别的入口或配套关系，文件已保留");
        }
        // 重入收口不产生新修订，也不重试已经失败或完成的组。
        for (GisFileMeta file : incoming) {
            if (file.getFileGroupId() != null && Objects.equals(file.getId(), getRequired(file.getFileGroupId()).getEntryFileId())
                    && "QUEUED".equals(files.selectById(file.getId()).getMetadataStatus()) && !ready.contains(file.getId())) {
                ready.add(file.getId());
            }
        }
        return ready;
    }

    /** 同批完全相同的重复成员不构成名称歧义，另存追溯记录而不污染规范组成员。 */
    private void preserveDuplicateReceipts(Long dataSetId, String progressId, Map<GisFileMeta, List<GisFileMeta>> repeated) {
        Map<Long, List<GisFileMeta>> byTarget = new LinkedHashMap<>();
        for (Map.Entry<GisFileMeta, List<GisFileMeta>> entry : repeated.entrySet()) {
            GisFileMeta original = entry.getKey();
            if (original.getFileGroupId() == null) continue;
            GisFileGroup target = getRequired(original.getFileGroupId());
            if (target.getFingerprint() == null && target.getDuplicateOfGroupId() == null) continue;
            Long effectiveId = target.getDuplicateOfGroupId() == null ? target.getId() : target.getDuplicateOfGroupId();
            byTarget.computeIfAbsent(effectiveId, ignored -> new ArrayList<>()).addAll(entry.getValue());
        }
        for (Map.Entry<Long, List<GisFileMeta>> entry : byTarget.entrySet()) {
            GisFileGroup target = getRequired(entry.getKey());
            GisFileFormat format = GisFileFormat.valueOf(target.getFormat());
            List<GisFileMeta> remaining = new ArrayList<>(entry.getValue());
            while (!remaining.isEmpty()) {
                Map<String, GisFileMeta> oneCopy = new LinkedHashMap<>();
                for (GisFileMeta file : remaining) oneCopy.putIfAbsent(role(format, file), file);
                GisFileGroup trace = createGroup(dataSetId, format, target.getName());
                List<GisFileMeta> members = new ArrayList<>(oneCopy.values());
                attach(trace, members);
                trace.setInputRevision(1L);
                trace.setDuplicateOfGroupId(target.getId());
                trace.setMatchedRevision(target.getInputRevision());
                trace.setEntryFileId(members.stream().filter(file -> "entry".equals(role(format, file))).map(GisFileMeta::getId).findFirst().orElse(null));
                setState(trace, "DUPLICATE", "PARKED", "DUPLICATE_INPUT", "同批相同成员已归入完整规范组，保留本次接收文件供追溯");
                groups.updateState(trace);
                groups.outcome(trace.getId(), progressId, "DUPLICATE");
                parkMembers(members, trace);
                remaining.removeAll(members);
            }
        }
    }

    private GisFileGroup createGroup(Long dataSetId, GisFileFormat format, String name) {
        GisFileGroup group = new GisFileGroup();
        group.setId(IdWorker.getId());
        group.setDataSetId(dataSetId);
        group.setName(name);
        group.setFormat(format.name());
        group.setInputRevision(0L);
        group.setAssemblyStatus("INCOMPLETE");
        group.setParseStatus("PARKED");
        group.setReadOptionsJson("{}");
        group.setCreateTime(LocalDateTime.now());
        group.setUpdateTime(group.getCreateTime());
        group.setDeleted(0);
        groups.insert(group);
        return group;
    }

    private void attach(GisFileGroup group, List<GisFileMeta> members) {
        for (GisFileMeta member : members) {
            String path = member.getOriginalName();
            if (groups.attach(member.getId(), group.getId(), path, "REGISTERED") != 1)
                throw new IllegalStateException("文件已被其他组占用，请重新读取接入结果");
            member.setFileGroupId(group.getId());
            member.setMemberPath(path);
        }
    }

    private GisFileGroup findSupplement(List<GisFileGroup> candidates, GisFileFormat format, List<GisFileMeta> members,
                                       Map<Long, SourceSnapshot> snapshots) {
        List<GisFileGroup> matches = candidates.stream().filter(group -> format.name().equals(group.getFormat())
                && members.stream().allMatch(file -> canSupplement(group, file, snapshots))).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    /** 只有同一受控来源目录及基名可跨接入补件，上传丢失目录信息后不推测跨批关系。 */
    private boolean canSupplement(GisFileGroup group, GisFileMeta member, Map<Long, SourceSnapshot> snapshots) {
        if ("DUPLICATE".equals(group.getAssemblyStatus()) || !"WORKSPACE".equals(member.getSourceType())) return false;
        GisFileFormat format = GisFileFormat.valueOf(group.getFormat());
        if (!format.acceptsMember(member.getOriginalName())) return false;
        List<GisFileMeta> current = groups.members(group.getId());
        if (current.isEmpty() || current.stream().anyMatch(file -> !"WORKSPACE".equals(file.getSourceType())
                || !Objects.equals(file.getWorkspaceCode(), member.getWorkspaceCode())
                || !directory(file.getRelativePath()).equals(directory(member.getRelativePath())))) return false;
        if (current.stream().anyMatch(file -> role(format, file).equals(role(format, member)))) return false;
        GisFileMeta entry = current.stream().filter(file -> format.entryExtensions()
                .contains(GisFileFormat.extension(file.getOriginalName()))).findFirst().orElse(null);
        if (entry != null) return matchesEvidence(entry, member, snapshots);
        return current.stream().allMatch(file -> sameStem(file.getOriginalName(), member.getOriginalName()));
    }

    private boolean reconcile(GisFileGroup group, String progressId, Map<Long, SourceSnapshot> snapshots) {
        List<GisFileMeta> members = groups.members(group.getId());
        GisFileFormat format = GisFileFormat.valueOf(group.getFormat());
        boolean hadSuccess = hasSuccessfulOutput(group, members);
        group.setInputRevision(group.getInputRevision() + 1);
        group.setFingerprint(null);
        group.setDuplicateOfGroupId(null);
        group.setMatchedRevision(null);
        List<GisFileMeta> entries = members.stream().filter(file -> format.entryExtensions()
                .contains(GisFileFormat.extension(file.getOriginalName()))).toList();
        group.setEntryFileId(entries.size() == 1 ? entries.getFirst().getId() : null);
        Set<String> extensions = members.stream().map(file -> GisFileFormat.extension(file.getOriginalName()))
                .collect(java.util.stream.Collectors.toSet());
        Set<String> required = format.requiredExtensions();
        if (format == GisFileFormat.MIF && entries.size() == 1 && sourceHeader(entries.getFirst(), snapshots).matches("(?is).*\\bColumns\\s+0\\b.*"))
            required = Set.of();
        if (entries.size() != 1 || !extensions.containsAll(required)) {
            setState(group, "INCOMPLETE", hadSuccess ? "REIMPORT_REQUIRED" : "PARKED", "MISSING_MEMBERS", "缺少入口或必要配套文件");
            parkMembers(members, group);
            groups.updateState(group);
            return false;
        }
        try {
            for (GisFileMeta member : members) applySnapshot(member, snapshots);
            String fingerprint = fingerprint(format, members);
            GisFileGroup canonical = groups.canonical(group.getDataSetId(), fingerprint);
            if (canonical != null && !canonical.getId().equals(group.getId())) {
                group.setDuplicateOfGroupId(canonical.getId());
                group.setMatchedRevision(canonical.getInputRevision());
                setState(group, "DUPLICATE", "PARKED", "DUPLICATE_INPUT", "完整输入已存在，保留本组成员供追溯");
                groups.outcome(group.getId(), progressId, "DUPLICATE");
                parkMembers(members, group);
                groups.updateState(group);
                return false;
            }
            group.setFingerprint(fingerprint);
        } catch (RuntimeException ex) {
            String code = ex instanceof ControlledWorkspaceResolver.SourceException source ? source.code() : "SOURCE_UNAVAILABLE";
            setState(group, "BLOCKED", "PARKED", code, "无法验证组成员内容：" + ex.getMessage());
            parkMembers(members, group);
            groups.updateState(group);
            return false;
        }
        String optionsError = optionsError(format, group.getReadOptionsJson());
        if (optionsError != null) {
            setState(group, "BLOCKED", "PARKED", "INPUT_OPTIONS_REQUIRED", optionsError);
            parkMembers(members, group);
            groups.updateState(group);
            return false;
        }
        if (hadSuccess) {
            setState(group, "COMPLETE", "REIMPORT_REQUIRED", "INPUT_CHANGED", "输入已变化，请显式重导；已有成果保持原样");
            groups.updateState(group);
            return false;
        }
        setState(group, "COMPLETE", "QUEUED", null, null);
        parkMembers(members, group);
        if (groups.queueEntry(group.getEntryFileId()) != 1) throw new IllegalStateException("文件组入口不能放行解析");
        groups.updateState(group);
        return true;
    }

    /** 摘要和格式头读取全部在领取数据集行锁前完成，单个源失败不阻断其他输入。 */
    private Map<Long, SourceSnapshot> prepareSources(List<GisFileMeta> incoming) {
        Map<Long, GisFileMeta> candidates = new LinkedHashMap<>();
        for (GisFileMeta file : incoming) if ("READY".equals(file.getFileStatus())) candidates.put(file.getId(), file);
        if (candidates.isEmpty()) return Map.of();
        Set<String> workspaceBuckets = candidates.values().stream().filter(file -> "WORKSPACE".equals(file.getSourceType()))
                .map(FileGroupService::sourceBucket).collect(java.util.stream.Collectors.toSet());
        Set<Long> assignedGroups = candidates.values().stream().map(GisFileMeta::getFileGroupId).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (!workspaceBuckets.isEmpty() || !assignedGroups.isEmpty()) {
            List<GisFileGroup> existing = groups.selectList(new LambdaQueryWrapper<GisFileGroup>()
                    .in(GisFileGroup::getDataSetId, incoming.stream().map(GisFileMeta::getDataSetId).distinct().toList()));
            for (GisFileGroup group : existing) {
                List<GisFileMeta> members = groups.members(group.getId());
                if (assignedGroups.contains(group.getId()) || members.stream().anyMatch(file -> workspaceBuckets.contains(sourceBucket(file)))) {
                    for (GisFileMeta file : members) candidates.putIfAbsent(file.getId(), file);
                }
            }
        }
        Map<Long, SourceSnapshot> snapshots = new LinkedHashMap<>();
        for (GisFileMeta file : candidates.values()) snapshots.put(file.getId(), prepareSource(file));
        return Map.copyOf(snapshots);
    }

    private SourceSnapshot prepareSource(GisFileMeta file) {
        String identity = sourceIdentity(file);
        try {
            // 缓存摘要也必须先验证当前登记快照，不能跳过源文件存在性和属性校验。
            Path path = sources.resolveFile(file).toAbsolutePath().normalize();
            BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            String sha256 = file.getSha256();
            if (sha256 == null || !sha256.matches("[a-fA-F0-9]{64}")) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = Files.newInputStream(path)) {
                    byte[] buffer = new byte[128 * 1024];
                    int length;
                    while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
                }
                sha256 = HexFormat.of().formatHex(digest.digest());
            }
            String header = "";
            if (Set.of("tab", "mif").contains(GisFileFormat.extension(file.getOriginalName()))) {
                try (InputStream input = Files.newInputStream(path)) {
                    header = new String(input.readNBytes(64 * 1024), StandardCharsets.ISO_8859_1);
                }
            }
            Path verified = sources.resolveFile(file).toAbsolutePath().normalize();
            BasicFileAttributes after = Files.readAttributes(verified, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!path.equals(verified) || !sameAttributes(before, after))
                throw new ControlledWorkspaceResolver.SourceException("SOURCE_CHANGED", "文件在摘要读取期间已变化");
            return new SourceSnapshot(identity, file.getFileGroupId(), path, before, sha256, header, null, null);
        } catch (Exception ex) {
            String code = ex instanceof ControlledWorkspaceResolver.SourceException source ? source.code() : "SOURCE_UNAVAILABLE";
            return new SourceSnapshot(identity, file.getFileGroupId(), null, null, null, "", code, ex.getMessage());
        }
    }

    /** 锁内只重验登记归属和文件属性，不再打开文件内容流。 */
    private void applySnapshot(GisFileMeta file, Map<Long, SourceSnapshot> snapshots) {
        SourceSnapshot snapshot = snapshots.get(file.getId());
        if (snapshot == null) throw new ControlledWorkspaceResolver.SourceException("SOURCE_UNAVAILABLE", "缺少锁前来源快照，请重新接入");
        if (snapshot.errorCode() != null) throw new ControlledWorkspaceResolver.SourceException(snapshot.errorCode(), snapshot.errorMessage());
        if (!snapshot.identity().equals(sourceIdentity(file))
                || (snapshot.fileGroupId() != null && !snapshot.fileGroupId().equals(file.getFileGroupId())))
            throw new ControlledWorkspaceResolver.SourceException("SOURCE_CHANGED", "登记来源或文件归属在读取期间已变化");
        try {
            Path path = sources.resolveFile(file).toAbsolutePath().normalize();
            BasicFileAttributes current = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!snapshot.path().equals(path) || !sameAttributes(snapshot.attributes(), current))
                throw new ControlledWorkspaceResolver.SourceException("SOURCE_CHANGED", "文件在组装前已变化");
        } catch (ControlledWorkspaceResolver.SourceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ControlledWorkspaceResolver.SourceException("SOURCE_UNAVAILABLE", "无法再次验证文件属性");
        }
        if (file.getSha256() != null && !file.getSha256().equalsIgnoreCase(snapshot.sha256()))
            throw new ControlledWorkspaceResolver.SourceException("SOURCE_CHANGED", "登记内容摘要在读取期间已变化");
        if (file.getSha256() == null) groups.setSha256(file.getId(), snapshot.sha256());
        file.setSha256(snapshot.sha256());
    }

    private static boolean sameAttributes(BasicFileAttributes first, BasicFileAttributes second) {
        return first.size() == second.size() && first.lastModifiedTime().equals(second.lastModifiedTime())
                && Objects.equals(first.fileKey(), second.fileKey());
    }

    private static String sourceIdentity(GisFileMeta file) {
        return String.join("\n", Objects.toString(file.getDataSetId(), ""), Objects.toString(file.getOriginalName(), ""),
                Objects.toString(file.getSourceType(), ""), Objects.toString(file.getWorkspaceCode(), ""),
                Objects.toString(file.getRelativePath(), ""), Objects.toString(file.getSourceModifiedAt(), ""),
                Objects.toString(file.getStorageType(), ""), Objects.toString(file.getStorageKey(), ""),
                Objects.toString(file.getSizeBytes(), ""));
    }

    private static String sourceErrorCode(GisFileMeta file, Map<Long, SourceSnapshot> snapshots) {
        SourceSnapshot snapshot = snapshots.get(file.getId());
        return snapshot != null && snapshot.errorCode() != null ? snapshot.errorCode() : "SOURCE_CHANGED";
    }

    /** 只保存本次读取的不可变结果，不跨接入缓存路径或源文件状态。 */
    private record SourceSnapshot(String identity, Long fileGroupId, Path path, BasicFileAttributes attributes,
                                  String sha256, String header, String errorCode, String errorMessage) { }

    /** 指纹只含格式、规范化成员角色和内容摘要，不含路径、批次或读取参数。 */
    static String fingerprint(GisFileFormat format, List<GisFileMeta> members) {
        if (hasDuplicateRoles(format, members)) throw new IllegalArgumentException("组内文件角色重复");
        Map<String, String> normalized = new TreeMap<>();
        for (GisFileMeta member : members) {
            if (member.getSha256() == null || !member.getSha256().matches("[a-fA-F0-9]{64}"))
                throw new IllegalArgumentException("完整组成员缺少内容摘要");
            normalized.put(role(format, member), member.getSha256().toLowerCase(Locale.ROOT));
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((format.name() + "\n").getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, String> entry : normalized.entrySet())
                digest.update((entry.getKey() + "\t" + entry.getValue() + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }

    @Transactional(readOnly = true)
    public GisFileGroup getRequired(Long groupId) {
        if (groupId == null || groupId < 1) throw new IllegalArgumentException("文件组ID无效");
        GisFileGroup group = groups.selectById(groupId);
        if (group == null) throw new IllegalArgumentException("文件组不存在或已删除");
        return group;
    }

    public List<GisFileMeta> members(Long groupId) { return groups.members(groupId); }

    /** 先锁组再锁入口，领取版本和输入修订必须同时有效。 */
    public GisFileGroup startExtraction(GisFileMeta claimedEntry) {
        return transactions.execute(status -> {
            if (claimedEntry.getFileGroupId() == null) return null;
            GisFileGroup group = groups.lock(claimedEntry.getFileGroupId());
            if (group == null || !"COMPLETE".equals(group.getAssemblyStatus())
                    || !Objects.equals(group.getEntryFileId(), claimedEntry.getId())
                    || !Set.of("QUEUED", "RUNNING").contains(group.getParseStatus())) return null;
            GisFileMeta current = files.selectActiveByIdForUpdate(claimedEntry.getId());
            if (current == null || !Objects.equals(current.getMetadataVersion(), claimedEntry.getMetadataVersion())
                    || !"RUNNING".equals(current.getMetadataStatus())) return null;
            group.setParseStatus("RUNNING");
            groups.updateState(group);
            return group;
        });
    }

    /** 必须由调用者置于最终结果提交事务，行锁保持到提交结束。 */
    public boolean lockCurrent(GisFileGroup snapshot) {
        if (snapshot == null) return false;
        GisFileGroup current = groups.lock(snapshot.getId());
        return current != null && Objects.equals(current.getInputRevision(), snapshot.getInputRevision())
                && Objects.equals(current.getEntryFileId(), snapshot.getEntryFileId())
                && "COMPLETE".equals(current.getAssemblyStatus()) && "RUNNING".equals(current.getParseStatus());
    }

    public void finishExtraction(GisFileGroup snapshot, String metadataStatus, String errorCode, String errorMessage) {
        transactions.executeWithoutResult(status -> {
            if (!lockCurrent(snapshot)) return;
            GisFileGroup current = groups.lock(snapshot.getId());
            boolean blocked = errorCode != null && Set.of("MISSING_CRS", "INPUT_OPTIONS_REQUIRED", "GDAL_UNAVAILABLE",
                    "GDAL_DRIVER_UNAVAILABLE", "DRIVER_UNAVAILABLE", "MISSING_GEOLOCATION", "MISSING_COORDINATE_FIELDS",
                    "MISSING_REQUIRED_MEMBERS", "SOURCE_UNAVAILABLE", "SOURCE_CHANGED").contains(errorCode);
            setState(current, blocked ? "BLOCKED" : "COMPLETE", blocked ? "PARKED" : metadataStatus, errorCode, errorMessage);
            if ("SOURCE_CHANGED".equals(errorCode) || "SOURCE_UNAVAILABLE".equals(errorCode)) current.setFingerprint(null);
            groups.updateState(current);
        });
    }

    @Transactional(readOnly = true)
    public GisFileGroupVO detail(Long id) {
        GisFileGroup group = getRequired(id);
        return new GisFileGroupVO(group, group.getDuplicateOfGroupId() == null ? id : group.getDuplicateOfGroupId(), members(id));
    }

    @Transactional(readOnly = true)
    public PageResult<List<GisFileGroup>> page(GisFileGroupVO.Query query) {
        long current = query.current() == null ? 1 : query.current();
        long size = query.size() == null ? 20 : query.size();
        if (current < 1 || size < 1 || size > 200) throw new IllegalArgumentException("分页范围无效");
        var where = new LambdaQueryWrapper<GisFileGroup>()
                .eq(query.dataSetId() != null, GisFileGroup::getDataSetId, query.dataSetId())
                .like(query.name() != null && !query.name().isBlank(), GisFileGroup::getName, query.name())
                .eq(query.format() != null, GisFileGroup::getFormat, query.format())
                .eq(query.assemblyStatus() != null, GisFileGroup::getAssemblyStatus, query.assemblyStatus())
                .eq(query.parseStatus() != null, GisFileGroup::getParseStatus, query.parseStatus())
                .ne(!Boolean.TRUE.equals(query.includeDuplicates()), GisFileGroup::getAssemblyStatus, "DUPLICATE")
                .orderByDesc(GisFileGroup::getCreateTime).orderByDesc(GisFileGroup::getId);
        Page<GisFileGroup> page = groups.selectPage(new Page<>(current, size), where);
        return new PageResult<>(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    public void updateOptions(Long id, GisFileGroupVO.Options options) {
        validateOptions(options);
        transactions.executeWithoutResult(status -> {
            GisFileGroup group = requireLocked(id);
            ensureIdle(group);
            if ("DUPLICATE".equals(group.getAssemblyStatus())) throw new IllegalStateException("重复记录请通过effectiveGroupId配置规范组");
            Map<String, String> normalized = new TreeMap<>();
            var saved = json.readTree(group.getReadOptionsJson());
            for (String field : List.of("sourceCrs", "xField", "yField")) {
                String value = saved.path(field).asText("");
                if (!value.isBlank()) normalized.put(field, value);
            }
            // 未提供的字段保持原值；显式空白字符串用于清除对应读取参数。
            Map<String, String> supplied = new LinkedHashMap<>();
            supplied.put("sourceCrs", options.sourceCrs());
            supplied.put("xField", options.xField());
            supplied.put("yField", options.yField());
            for (var field : supplied.entrySet()) {
                if (field.getValue() == null) continue;
                if (field.getValue().isBlank()) normalized.remove(field.getKey());
                else normalized.put(field.getKey(), field.getValue().trim());
            }
            String sourceCrs = normalized.get("sourceCrs");
            if (sourceCrs != null && sourceCrs.matches("(?i)EPSG:[1-9][0-9]*|OGC:CRS84"))
                normalized.put("sourceCrs", sourceCrs.toUpperCase(Locale.ROOT));
            validateOptions(new GisFileGroupVO.Options(normalized.get("sourceCrs"), normalized.get("xField"), normalized.get("yField")));
            String encoded = json.writeValueAsString(normalized);
            if (json.readTree(encoded).equals(json.readTree(group.getReadOptionsJson()))) return;
            group.setReadOptionsJson(encoded);
            group.setInputRevision(group.getInputRevision() + 1);
            if (hasSuccessfulOutput(group, members(id))) {
                setState(group, "COMPLETE", "REIMPORT_REQUIRED", "INPUT_CHANGED", "读取配置已变化，请显式重导；已有成果保持原样");
                groups.updateState(group);
                return;
            }
            if (group.getFingerprint() == null || group.getEntryFileId() == null) {
                groups.updateState(group);
                return;
            }
            queueIfReady(group);
        });
    }

    public void retry(Long id) {
        transactions.executeWithoutResult(status -> {
            GisFileGroup group = requireLocked(id);
            ensureIdle(group);
            if (hasVectorReferences(members(id))) throw new IllegalStateException("组成员仍被矢量成果引用，请显式删除相关成果后重导");
            queueIfReady(group);
        });
    }

    private void queueIfReady(GisFileGroup group) {
        if (Set.of("SOURCE_CHANGED", "SOURCE_UNAVAILABLE").contains(Objects.toString(group.getErrorCode(), "")))
            throw new IllegalStateException("原位源文件已变化或不可用，请保留源文件、删除本组登记后重新接入");
        if (group.getEntryFileId() == null || group.getFingerprint() == null || "DUPLICATE".equals(group.getAssemblyStatus()))
            throw new IllegalStateException("文件组尚未完整或已匹配其他完整组");
        String error = optionsError(GisFileFormat.valueOf(group.getFormat()), group.getReadOptionsJson());
        if (error != null) {
            setState(group, "BLOCKED", "PARKED", "INPUT_OPTIONS_REQUIRED", error);
            groups.updateState(group);
            return;
        }
        groups.clearEntryMetadata(group.getEntryFileId());
        if (groups.queueEntry(group.getEntryFileId()) != 1) throw new IllegalStateException("文件组入口无法重新解析");
        setState(group, "COMPLETE", "QUEUED", null, null);
        groups.updateState(group);
        events.publishEvent(new FileSpatialMetadataService.MetadataReady(List.of(group.getEntryFileId())));
    }

    /** 旧物理文件删除接口在完成成员删除后调用，禁止留下可复用的陈旧指纹。 */
    public void invalidateMember(Long groupId, Long fileId) {
        GisFileGroup group = groups.lock(groupId);
        if (group == null) return;
        boolean hadSuccess = hasSuccessfulOutput(group, members(groupId));
        group.setInputRevision(group.getInputRevision() + 1);
        group.setFingerprint(null);
        group.setDuplicateOfGroupId(null);
        group.setMatchedRevision(null);
        if (Objects.equals(group.getEntryFileId(), fileId)) group.setEntryFileId(null);
        setState(group, "INCOMPLETE", hadSuccess ? "REIMPORT_REQUIRED" : "PARKED", "MEMBER_REMOVED", "组成员已删除，需要重新检查输入");
        groups.updateState(group);
        parkMembers(members(groupId), group);
    }

    public void delete(Long id) {
        transactions.executeWithoutResult(status -> {
            GisFileGroup observed = getRequired(id);
            if (dataSets.selectActiveByIdForUpdate(observed.getDataSetId()) == null) throw new IllegalStateException("数据集已删除");
            GisFileGroup group = requireLocked(id);
            ensureIdle(group);
            if (groups.selectCount(new LambdaQueryWrapper<GisFileGroup>()
                    .eq(GisFileGroup::getDuplicateOfGroupId, id)) > 0)
                throw new IllegalStateException("文件组仍被重复输入追溯记录引用，请先删除这些重复追溯记录");
            List<GisFileMeta> members = members(id).stream().sorted(Comparator.comparing(GisFileMeta::getId)).toList();
            for (GisFileMeta file : members) {
                files.selectActiveByIdForUpdate(file.getId());
                if (!processingInputs.selectActiveByFileMetaId(file.getId()).isEmpty() || vectors.countSourceReferences(file.getId()) > 0)
                    throw new IllegalStateException("组成员仍被处理任务或矢量成果引用，暂不能删除");
                if (Set.of("WAITING", "RUNNING").contains(file.getMetadataStatus())) throw new IllegalStateException("组成员仍在接入或解析");
                if ("UPLOAD".equals(file.getSourceType()) && !"LOCAL".equals(file.getStorageType()))
                    throw new IllegalStateException("暂不支持删除非本地托管文件");
            }
            uploads.stageStoredForTransactionalDeletion(members.stream().filter(file -> "UPLOAD".equals(file.getSourceType()))
                    .map(GisFileMeta::getStorageKey).filter(Objects::nonNull).toList());
            for (GisFileMeta file : members) {
                processingInputs.detachTerminalByFileMetaId(file.getId());
                files.physicallyDeleteActiveById(file.getId());
            }
            groups.physicallyDelete(id);
            long size = members.stream().filter(file -> "READY".equals(file.getFileStatus())).mapToLong(GisFileMeta::getSizeBytes).sum();
            int count = (int) members.stream().filter(file -> "READY".equals(file.getFileStatus())).count();
            if (dataSets.adjustFileStatistics(group.getDataSetId(), -count, -size) != 1) throw new IllegalStateException("数据集文件统计更新失败");
            events.publishEvent(new FileSpatialMetadataService.MetadataProgress(members.stream().map(GisFileMeta::getId).toList(), false));
        });
    }

    private GisFileGroup requireLocked(Long id) {
        GisFileGroup group = groups.lock(id);
        if (group == null) throw new IllegalArgumentException("文件组不存在或已删除");
        return group;
    }

    private void ensureIdle(GisFileGroup group) {
        if (Set.of("QUEUED", "RUNNING").contains(group.getParseStatus())) throw new IllegalStateException("文件组正在解析，请等待结束");
    }

    private boolean hasVectorReferences(List<GisFileMeta> members) {
        return members.stream().anyMatch(file -> vectors.countSourceReferences(file.getId()) > 0);
    }

    private boolean hasSuccessfulOutput(GisFileGroup group, List<GisFileMeta> members) {
        return Set.of("COMPLETED", "REIMPORT_REQUIRED").contains(group.getParseStatus())
                || members.stream().anyMatch(file -> "COMPLETED".equals(file.getMetadataStatus())) || hasVectorReferences(members);
    }

    private void parkMembers(List<GisFileMeta> members, GisFileGroup group) {
        for (GisFileMeta file : members) groups.park(file.getId(), group.getErrorCode(), group.getErrorMessage());
    }

    private static void setState(GisFileGroup group, String assembly, String parse, String code, String message) {
        group.setAssemblyStatus(assembly);
        group.setParseStatus(parse);
        group.setErrorCode(code);
        group.setErrorMessage(message == null ? null : message.substring(0, Math.min(message.length(), 1000)));
    }

    private String optionsError(GisFileFormat format, String options) {
        var node = json.readTree(options == null ? "{}" : options);
        if (format == GisFileFormat.CSV && (node.path("sourceCrs").asText("").isBlank()
                || node.path("xField").asText("").isBlank() || node.path("yField").asText("").isBlank()))
            return "CSV需要明确的源坐标系以及X、Y字段";
        if ((format == GisFileFormat.DXF || format == GisFileFormat.DWG) && node.path("sourceCrs").asText("").isBlank())
            return "CAD需要明确源坐标系";
        return null;
    }

    private void validateOptions(GisFileGroupVO.Options options) {
        String crs = options.sourceCrs();
        if (crs != null && !crs.isBlank() && !crs.matches("(?i)EPSG:[1-9][0-9]*|OGC:CRS84")
                && !crs.matches("(?is)(GEOGCS|PROJCS|GEOGCRS|PROJCRS|COMPOUNDCRS|BOUNDCRS|VERTCRS)\\s*\\[.*"))
            throw new IllegalArgumentException("源坐标系必须是EPSG编码、OGC:CRS84或明确WKT，禁止文件和URL引用");
        if (options.xField() != null && options.xField().equals(options.yField())) throw new IllegalArgumentException("X、Y字段必须不同");
    }

    static boolean matches(GisFileMeta entry, GisFileMeta member) {
        GisFileFormat format = GisFileFormat.fromFileName(entry.getOriginalName());
        if (format == null || !format.acceptsMember(member.getOriginalName())) return false;
        if (entry == member) return true;
        return sameStem(entry.getOriginalName(), member.getOriginalName());
    }

    private boolean matchesEvidence(GisFileMeta entry, GisFileMeta member, Map<Long, SourceSnapshot> snapshots) {
        GisFileFormat format = GisFileFormat.fromFileName(entry.getOriginalName());
        if (format != null && format.isRaster() && "tab".equals(GisFileFormat.extension(member.getOriginalName())) && isRasterTab(member, snapshots)) {
            var reference = java.util.regex.Pattern.compile("(?im)^\\s*File\\s+\"([^\"]+)\"").matcher(sourceHeader(member, snapshots));
            if (reference.find()) return reference.group(1).equalsIgnoreCase(entry.getOriginalName());
            return false;
        }
        return matches(entry, member);
    }

    static boolean hasDuplicateRoles(GisFileFormat format, List<GisFileMeta> members) {
        return members.stream().map(file -> role(format, file)).distinct().count() != members.size();
    }

    private static String role(GisFileFormat format, GisFileMeta file) {
        String extension = GisFileFormat.extension(file.getOriginalName());
        if (format.entryExtensions().contains(extension)) return "entry";
        if (Set.of("tfw", "tifw", "tiffw", "wld", "jgw", "jpgw", "jpegw", "pgw", "pngw", "gfw", "gifw").contains(extension))
            return "worldfile";
        return extension;
    }

    private static String sourceBucket(GisFileMeta file) {
        return "WORKSPACE".equals(file.getSourceType())
                ? "WORKSPACE:" + file.getWorkspaceCode() + ":" + directory(file.getRelativePath())
                : "UPLOAD";
    }

    private static String directory(String path) {
        int split = path == null ? -1 : path.lastIndexOf('/');
        return split < 0 ? "" : path.substring(0, split);
    }

    private static boolean sameStem(String first, String second) { return stem(first).equals(stem(second)); }

    private static String stem(String name) {
        String value = name.toLowerCase(Locale.ROOT);
        String extension = GisFileFormat.extension(value);
        if (!extension.isEmpty()) value = value.substring(0, value.length() - extension.length() - 1);
        if (Set.of("aux.xml", "msk", "ovr", "idx").contains(extension)) {
            GisFileFormat attached = GisFileFormat.fromFileName(value);
            if (attached != null) value = value.substring(0, value.lastIndexOf('.'));
        }
        return value;
    }

    private boolean isRasterTab(GisFileMeta file, Map<Long, SourceSnapshot> snapshots) {
        return GisFileFormat.fromFileName(file.getOriginalName()) == GisFileFormat.TAB
                && sourceHeader(file, snapshots).matches("(?is).*\\bType\\s+RASTER\\b.*");
    }

    /** 识别过程只读取锁前已经取得的有界格式头。 */
    private String sourceHeader(GisFileMeta file, Map<Long, SourceSnapshot> snapshots) {
        SourceSnapshot snapshot = snapshots.get(file.getId());
        return snapshot == null ? "" : snapshot.header();
    }
}
