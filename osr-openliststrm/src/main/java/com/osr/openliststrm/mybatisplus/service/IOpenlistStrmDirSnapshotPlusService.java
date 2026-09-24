package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmDirSnapshotPlus;

import java.util.Collection;
import java.util.Date;
import java.util.Map;

public interface IOpenlistStrmDirSnapshotPlusService extends IService<OpenlistStrmDirSnapshotPlus> {

    /**
     * 取某棵子树下全部快照，键是目录路径。
     *
     * @param rootPath 子树根（规范化后不带末尾斜杠）
     */
    Map<String, OpenlistStrmDirSnapshotPlus> loadSubtree(String rootPath);

    /** 按路径写入或覆盖快照 */
    void upsert(Collection<OpenlistStrmDirSnapshotPlus> snapshots);

    /** 删掉这些路径的快照（它们这次不满足可跳过的条件了） */
    void removePaths(Collection<String> paths);

    /**
     * 删掉子树下 {@code scanned_time} 早于 {@code before} 的快照：全量扫描一轮下来没再确认过的，
     * 就是目录已经不存在了。只能在全量扫描之后调用——增量扫描跳过的目录本来就不会被确认。
     *
     * @return 删除条数
     */
    int purgeStale(String rootPath, Date before);
}
