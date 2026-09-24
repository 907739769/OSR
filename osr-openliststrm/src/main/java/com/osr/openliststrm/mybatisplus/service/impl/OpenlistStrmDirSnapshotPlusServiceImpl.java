package com.osr.openliststrm.mybatisplus.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmDirSnapshotPlus;
import com.osr.openliststrm.mybatisplus.mapper.OpenlistStrmDirSnapshotPlusMapper;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmDirSnapshotPlusService;
import com.osr.openliststrm.rename.cleanup.ArtifactPaths;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class OpenlistStrmDirSnapshotPlusServiceImpl
        extends ServiceImpl<OpenlistStrmDirSnapshotPlusMapper, OpenlistStrmDirSnapshotPlus>
        implements IOpenlistStrmDirSnapshotPlusService {

    /** IN 子句一次放多少个哈希，免得一轮扫出几万个目录时拼出超长 SQL */
    private static final int CHUNK = 500;

    @Override
    public Map<String, OpenlistStrmDirSnapshotPlus> loadSubtree(String rootPath) {
        String root = StringUtils.removeEnd(StringUtils.defaultString(rootPath), "/");
        return list(subtree(root)).stream()
                .collect(Collectors.toMap(OpenlistStrmDirSnapshotPlus::getDirPath, s -> s, (a, b) -> a));
    }

    @Override
    public void upsert(Collection<OpenlistStrmDirSnapshotPlus> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        Map<String, OpenlistStrmDirSnapshotPlus> byHash = new HashMap<>();
        for (OpenlistStrmDirSnapshotPlus s : snapshots) {
            s.setPathHash(hash(s.getDirPath()));
            byHash.put(s.getPathHash(), s);
        }
        for (List<String> chunk : chunks(byHash.keySet())) {
            for (OpenlistStrmDirSnapshotPlus existing : list(new LambdaQueryWrapper<OpenlistStrmDirSnapshotPlus>()
                    .select(OpenlistStrmDirSnapshotPlus::getId, OpenlistStrmDirSnapshotPlus::getPathHash)
                    .in(OpenlistStrmDirSnapshotPlus::getPathHash, chunk))) {
                byHash.get(existing.getPathHash()).setId(existing.getId());
            }
        }
        saveOrUpdateBatch(byHash.values());
    }

    @Override
    public void removePaths(Collection<String> paths) {
        if (paths.isEmpty()) {
            return;
        }
        Set<String> hashes = paths.stream().map(OpenlistStrmDirSnapshotPlusServiceImpl::hash).collect(Collectors.toSet());
        for (List<String> chunk : chunks(hashes)) {
            remove(new LambdaQueryWrapper<OpenlistStrmDirSnapshotPlus>().in(OpenlistStrmDirSnapshotPlus::getPathHash, chunk));
        }
    }

    @Override
    public int purgeStale(String rootPath, Date before) {
        String root = StringUtils.removeEnd(StringUtils.defaultString(rootPath), "/");
        return getBaseMapper().delete(subtree(root).lt(OpenlistStrmDirSnapshotPlus::getScannedTime, before));
    }

    /**
     * 子树条件：根目录本身 + 以「根 + /」开头的路径。补分隔符与转义 LIKE 通配符的理由同
     * {@code StrmServiceImpl#subtreeLikePrefix}：不补分隔符 /电视剧/三体 会连 /电视剧/三体2 一起捞，
     * 不转义的话路径里的 _ 在 LIKE 里是任意单字符。
     */
    private static LambdaQueryWrapper<OpenlistStrmDirSnapshotPlus> subtree(String root) {
        String prefix = ArtifactPaths.escapeLike(root) + "/";
        return new LambdaQueryWrapper<OpenlistStrmDirSnapshotPlus>()
                .and(w -> w.eq(OpenlistStrmDirSnapshotPlus::getDirPath, root)
                        .or().likeRight(OpenlistStrmDirSnapshotPlus::getDirPath, prefix));
    }

    private static List<List<String>> chunks(Collection<String> values) {
        List<String> all = new ArrayList<>(values);
        List<List<String>> chunks = new ArrayList<>();
        for (int i = 0; i < all.size(); i += CHUNK) {
            chunks.add(all.subList(i, Math.min(i + CHUNK, all.size())));
        }
        return chunks;
    }

    static String hash(String path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(path.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
