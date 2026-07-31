package com.osr.openliststrm.orphan;

import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameOrphanPlus;

import java.util.Date;

/**
 * 孤儿判定的决策逻辑：把"本次检测结果"与"rename_orphan 表里已有的记录"做比对，
 * 决定插入 / 更新 / 删除（已恢复正常）/ 跳过（已忽略且问题仍在，不重复提醒）。
 * 不做任何 I/O，方便单测覆盖所有分支。
 */
public final class OrphanReconciler {

    private OrphanReconciler() {
    }

    public enum Action {
        INSERT, UPDATE, DELETE, SKIP
    }

    public record Decision(Action action, RenameOrphanPlus toPersist) {
    }

    private static final Decision SKIP_DECISION = new Decision(Action.SKIP, null);

    /**
     * @param detail  本次扫描到的重命名明细
     * @param existing 该 detail 在 rename_orphan 表中已有的记录，没有则为 null
     * @param reason   本次检测到的孤儿原因（local_missing / source_missing），没问题则为 null
     * @param now      发现/恢复时间
     */
    public static Decision reconcile(RenameDetailPlus detail, RenameOrphanPlus existing, String reason, Date now) {
        if (reason == null) {
            return existing != null ? new Decision(Action.DELETE, existing) : SKIP_DECISION;
        }
        if (existing != null && "2".equals(existing.getStatus())) {
            return SKIP_DECISION;
        }
        RenameOrphanPlus target = existing != null ? existing : new RenameOrphanPlus();
        target.setDetailId(detail.getId());
        target.setNewPath(detail.getNewPath());
        target.setNewName(detail.getNewName());
        target.setTitle(detail.getTitle());
        target.setYear(detail.getYear());
        target.setMediaType(detail.getMediaType());
        target.setReason(reason);
        target.setStatus("0");
        target.setFoundTime(now);
        return new Decision(existing != null ? Action.UPDATE : Action.INSERT, target);
    }
}
