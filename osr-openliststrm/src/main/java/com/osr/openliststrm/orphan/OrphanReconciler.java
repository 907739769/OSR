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

    /**
     * 反向扫描（有文件、无记录）的判定。与 {@link #reconcile} 的区别只在于没有 detail 可挂靠：
     * {@code detail_id} 留 null，去重靠 (new_path, new_name) 这对路径。
     * <p>
     * 「已忽略仍跳过」的语义与正向一致——用户说过不管的东西，不该每轮扫描再冒出来一次。
     *
     * @param newPath   目录（empty_dir / metadata_only 时就是目录本身）
     * @param newName   文件名；目录级发现传 null
     * @param title     展示用标题，一般取文件名或目录名
     * @param mediaType 媒体类型，判不出来传 null
     * @param existing  该路径在 rename_orphan 里已有的记录，没有则为 null
     * @param reason    见 {@link OrphanReason}
     */
    public static Decision reconcileExtra(String newPath, String newName, String title, String mediaType,
                                          RenameOrphanPlus existing, String reason, Date now) {
        if (reason == null) {
            return existing != null ? new Decision(Action.DELETE, existing) : SKIP_DECISION;
        }
        if (existing != null && "2".equals(existing.getStatus())) {
            return SKIP_DECISION;
        }
        RenameOrphanPlus target = existing != null ? existing : new RenameOrphanPlus();
        target.setDetailId(null);
        target.setNewPath(newPath);
        target.setNewName(newName);
        target.setTitle(truncate(title, 255));
        target.setMediaType(mediaType);
        target.setReason(reason);
        target.setStatus("0");
        target.setFoundTime(now);
        return new Decision(existing != null ? Action.UPDATE : Action.INSERT, target);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
