package com.osr.openliststrm.helper;

import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmPlus;
import com.osr.openliststrm.mybatisplus.service.IOpenlistStrmPlusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class StrmHelper {

    /** path+fileName 拼接 key 用的分隔符，真实路径/文件名不可能包含该字符，避免拼接歧义导致 key 碰撞 */
    private static final char KEY_SEPARATOR = 1;

    @Autowired
    private IOpenlistStrmPlusService openlistStrmPlusService;

    /**
     * 新增或更新 strm 记录。
     * <p>
     * 注意：openlist_strm 表上 (strm_path, strm_file_name) 并无唯一约束
     * （曾经加过唯一索引 idx_path_name，但已被 20260626-delindex.sql 删除；strm_path 字段过长
     * 无法重建唯一索引，不要再尝试恢复），因此这里必须显式查询是否已存在再决定 insert/update，
     * 不能依赖插入时捕获唯一键冲突异常。
     * <p>
     * <b>同步执行，不要再包 {@code AsyncManager}</b>：异步（延迟 10ms 调度）会让本方法返回时记录
     * 尚未落库，而下游普遍靠读库判重——{@code StrmServiceImpl#strmOneFile} 开头的 {@link #existsStrm}、
     * {@code CopyRecoveryTask#backfillMissingStrm} 的 {@code NOT EXISTS openlist_strm} 都是。
     * 表上没有唯一约束兜底，这个窗口就是实打实的重复行：兜底恢复判成功后刚生成 STRM，
     * backfill 立刻用旧快照又捞一次，两次 addStrm 各自「查不到 → insert」，同一文件留下两条一模一样的记录。
     * 目录级的 {@link #batchAddStrm} 早就是同步的（注释里写明「方法返回即已入库」），单文件路径与之对齐。
     * 调用方（{@code strmOneFile}）本就跑在后台虚拟线程上、且刚做完写文件的 IO，多一次库往返可以忽略。
     */
    public void addStrm(String strmPath, String strmFileName, String status) {
        try {
            // 表上无唯一约束，历史脏数据可能存在同 path+fileName 多行；用 LIMIT 1 避免
            // .one() 在命中多行时抛 TooManyResultsException
            OpenlistStrmPlus existing = openlistStrmPlusService.lambdaQuery()
                    .eq(OpenlistStrmPlus::getStrmPath, strmPath)
                    .eq(OpenlistStrmPlus::getStrmFileName, strmFileName)
                    .select(OpenlistStrmPlus::getStrmId)
                    .last("LIMIT 1")
                    .one();
            if (existing != null) {
                openlistStrmPlusService.lambdaUpdate()
                        .eq(OpenlistStrmPlus::getStrmId, existing.getStrmId())
                        .set(OpenlistStrmPlus::getStrmStatus, status)
                        .update();
            } else {
                OpenlistStrmPlus strm = new OpenlistStrmPlus();
                strm.setStrmPath(strmPath);
                strm.setStrmFileName(strmFileName);
                strm.setStrmStatus(status);
                openlistStrmPlusService.save(strm);
            }
        } catch (Exception e) {
            // 保持原异步实现的语义：写记录失败不往上抛，.strm 文件本身已经写好了，
            // 抛出去会让 strmOneFile 的 catch 分支再调一次 addStrm，把一次失败放大成两次
            log.error("Error adding strm: path={}, fileName={}", strmPath, strmFileName, e);
        }
    }

    /**
     * 构造一条待批量写入的 strm 记录（供 {@link #batchAddStrm} 使用）。
     */
    public OpenlistStrmPlus newRecord(String strmPath, String strmFileName, String status) {
        OpenlistStrmPlus strm = new OpenlistStrmPlus();
        strm.setStrmPath(strmPath);
        strm.setStrmFileName(strmFileName);
        strm.setStrmStatus(status);
        return strm;
    }

    /**
     * {@code strmPath + strmFileName} 的映射 key，供 {@link #batchAddStrm} 与调用方共用，
     * 避免各自用 {@code path + " " + fileName} 拼接导致路径/文件名本身含空格时发生碰撞。
     */
    public static String recordKey(String strmPath, String strmFileName) {
        return strmPath + KEY_SEPARATOR + strmFileName;
    }

    /**
     * 批量新增/更新 strm 记录，用于目录级批量 STRM 生成场景。
     * <p>
     * openlist_strm 无唯一约束，无法用单条 ON DUPLICATE KEY UPSERT，因此按调用方传入的
     * "path+fileName -> strmId" 映射（调用方已一次性查出该目录树下所有已存在记录）分组：
     * 命中的回填 strmId 走 updateBatchById，未命中的走 saveBatch。相比每文件单独调用
     * {@link #addStrm}（各自异步调度 + 一次查询 + 一次写入），把 2N 次数据库往返压缩为 2 次批量写入
     * （加上调用方已经做过的那 1 次查询）。
     * <p>
     * 调用方（{@code getData} 的 BFS 批处理）已运行在后台虚拟线程上，这里同步执行以保证
     * 方法返回时记录已入库，不再额外包一层 AsyncManager 延迟调度。
     */
    public void batchAddStrm(List<OpenlistStrmPlus> records, Map<String, Integer> existingIdByKey) {
        if (records == null || records.isEmpty()) {
            return;
        }
        try {
            List<OpenlistStrmPlus> toInsert = new ArrayList<>();
            List<OpenlistStrmPlus> toUpdate = new ArrayList<>();
            for (OpenlistStrmPlus record : records) {
                Integer existingId = existingIdByKey.get(recordKey(record.getStrmPath(), record.getStrmFileName()));
                if (existingId != null) {
                    record.setStrmId(existingId);
                    toUpdate.add(record);
                } else {
                    toInsert.add(record);
                }
            }
            if (!toInsert.isEmpty()) {
                openlistStrmPlusService.saveBatch(toInsert);
            }
            if (!toUpdate.isEmpty()) {
                openlistStrmPlusService.updateBatchById(toUpdate);
            }
        } catch (Exception e) {
            // 调用方依赖"方法返回即已入库"这一保证（下游 isCopyDone/existsStrm 监控读库判断），
            // 批量写失败时不能只打日志静默吞掉，必须抛出让调用方感知整目录/整树数据未落库
            log.error("批量写入strm记录失败，数量={}", records.size(), e);
            throw new RuntimeException("批量写入strm记录失败", e);
        }
    }

    /**
     * 判断strm的文件是否处理过
     */
    public boolean existsStrm(String strmPath, String strmFileName) {
        return openlistStrmPlusService.lambdaQuery()
                .eq(OpenlistStrmPlus::getStrmPath, strmPath)
                .eq(OpenlistStrmPlus::getStrmFileName, strmFileName)
                .eq(OpenlistStrmPlus::getStrmStatus, "1")
                .exists();
    }
}
