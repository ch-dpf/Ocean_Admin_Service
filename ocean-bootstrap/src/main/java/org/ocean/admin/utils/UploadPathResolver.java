package org.ocean.admin.utils;

import lombok.RequiredArgsConstructor;
import org.ocean.admin.config.FileUploadConfig;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 统一解析 uploads 及其子目录的物理路径。
 * <p>
 * 当工作目录位于仓库根目录时，优先回退到服务模块根目录（如 ./server），
 * 避免误命中仓库根目录下的 uploads。
 */
@Component
@RequiredArgsConstructor
public class UploadPathResolver {

    private final FileUploadConfig fileUploadConfig;

    /**
     * 解析上传根目录
     * @return 文件对象
     * @throws IOException  io异常
     */
    public File resolveUploadsRoot() throws IOException {
        String configuredBasePath = fileUploadConfig == null ? null : fileUploadConfig.getBasePath();
        return resolveConfiguredPath(configuredBasePath, "uploads");
    }

    /**
     * 解析配置路径
     * @param configuredPath 配置路径
     * @param fallbackLeafDirectory 备用叶子目录
     * @return 文件对象
     * @throws IOException io异常
     */
    public File resolveConfiguredPath(String configuredPath, String fallbackLeafDirectory) throws IOException {
        LinkedHashSet<File> candidates = new LinkedHashSet<>();
        List<File> serviceRoots = resolveServiceRootCandidates();

        String trimmed = configuredPath == null ? "" : configuredPath.trim();
        if (!trimmed.isEmpty()) {
            File configured = new File(trimmed);
            if (configured.isAbsolute()) {
                candidates.add(configured);
            } else {
                for (File serviceRoot : serviceRoots) {
                    candidates.add(new File(serviceRoot, trimmed));
                }
            }
        }

        if (fallbackLeafDirectory != null && !fallbackLeafDirectory.isBlank()) {
            for (File serviceRoot : serviceRoots) {
                candidates.add(new File(serviceRoot, fallbackLeafDirectory));
            }
        }

        if (candidates.isEmpty()) {
            throw new IOException("未找到可用的上传目录候选路径");
        }

        File firstCandidate = null;
        for (File candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            File canonical = candidate.getCanonicalFile();
            if (firstCandidate == null) {
                firstCandidate = canonical;
            }
            if (canonical.exists()) {
                return canonical;
            }
        }

        return firstCandidate;
    }

    /**
     * 解析服务根目录候选项
     * @return 文件对象列表
     * @throws IOException io异常
     */
    private List<File> resolveServiceRootCandidates() throws IOException {
        LinkedHashSet<File> candidates = new LinkedHashSet<>();
        LinkedHashSet<File> fallbackAnchors = new LinkedHashSet<>();

        addServiceRootCandidates(candidates, fallbackAnchors, new File(System.getProperty("user.dir")));

        File appHome = new ApplicationHome(UploadPathResolver.class).getDir();
        if (appHome != null) {
            addServiceRootCandidates(candidates, fallbackAnchors, appHome);
        }

        // Only fallback to anchors when no service-root-like directory is found.
        if (candidates.isEmpty()) {
            candidates.addAll(fallbackAnchors);
        }

        if (candidates.isEmpty()) {
            candidates.add(new File(".").getCanonicalFile());
        }

        return new ArrayList<>(candidates);
    }

    /**
     * 添加服务根候选项
     * @param candidates 候选项列表
     * @param fallbackAnchors 备用锚点列表
     * @param anchor 锚
     * @throws IOException io异常
     */
    private void addServiceRootCandidates(LinkedHashSet<File> candidates,
                                          LinkedHashSet<File> fallbackAnchors,
                                          File anchor) throws IOException {
        if (anchor == null) {
            return;
        }

        File canonicalAnchor = anchor.getCanonicalFile();
        fallbackAnchors.add(canonicalAnchor);
        File current = canonicalAnchor;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            File serverChild = new File(current, "server");
            if (looksLikeServiceRoot(serverChild)) {
                candidates.add(serverChild.getCanonicalFile());
            }
            if (looksLikeServiceRoot(current)) {
                candidates.add(current.getCanonicalFile());
            }
            current = current.getParentFile();
        }
    }

    /**
     * 疑似服务根目录
     * @param dir 目录
     * @return true/false
     */
    private boolean looksLikeServiceRoot(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return false;
        }
        return new File(dir, "pom.xml").exists() || new File(dir, "src" + File.separator + "main").exists();
    }
}

