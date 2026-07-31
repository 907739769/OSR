package com.osr.openliststrm.orphan;

import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.domain.RenameOrphanPlus;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class OrphanReconcilerTest {

    private RenameDetailPlus detail() {
        RenameDetailPlus d = new RenameDetailPlus();
        d.setId(42);
        d.setNewPath("/data/media/Movies/Inception (2010)");
        d.setNewName("Inception (2010).strm");
        d.setTitle("盗梦空间");
        d.setYear("2010");
        d.setMediaType("movie");
        return d;
    }

    @Test
    void reconcile_无问题且没有已有记录_跳过() {
        OrphanReconciler.Decision decision = OrphanReconciler.reconcile(detail(), null, null, new Date());
        assertEquals(OrphanReconciler.Action.SKIP, decision.action());
    }

    @Test
    void reconcile_无问题但存在待处理的已有记录_删除已恢复正常的记录() {
        RenameOrphanPlus existing = new RenameOrphanPlus();
        existing.setId(1);
        existing.setStatus("0");
        OrphanReconciler.Decision decision = OrphanReconciler.reconcile(detail(), existing, null, new Date());
        assertEquals(OrphanReconciler.Action.DELETE, decision.action());
        assertSame(existing, decision.toPersist());
    }

    @Test
    void reconcile_有问题且没有已有记录_插入新记录() {
        Date now = new Date();
        OrphanReconciler.Decision decision = OrphanReconciler.reconcile(detail(), null, "source_missing", now);
        assertEquals(OrphanReconciler.Action.INSERT, decision.action());
        RenameOrphanPlus persist = decision.toPersist();
        assertEquals(42, persist.getDetailId());
        assertEquals("/data/media/Movies/Inception (2010)", persist.getNewPath());
        assertEquals("Inception (2010).strm", persist.getNewName());
        assertEquals("盗梦空间", persist.getTitle());
        assertEquals("source_missing", persist.getReason());
        assertEquals("0", persist.getStatus());
        assertEquals(now, persist.getFoundTime());
    }

    @Test
    void reconcile_有问题且已有待处理记录_更新原因和发现时间() {
        RenameOrphanPlus existing = new RenameOrphanPlus();
        existing.setId(7);
        existing.setStatus("0");
        existing.setReason("local_missing");
        Date now = new Date();
        OrphanReconciler.Decision decision = OrphanReconciler.reconcile(detail(), existing, "source_missing", now);
        assertEquals(OrphanReconciler.Action.UPDATE, decision.action());
        assertSame(existing, decision.toPersist());
        assertEquals("source_missing", existing.getReason());
        assertEquals(now, existing.getFoundTime());
    }

    @Test
    void reconcile_有问题但已被人工忽略_跳过不重复提醒() {
        RenameOrphanPlus existing = new RenameOrphanPlus();
        existing.setId(9);
        existing.setStatus("2");
        OrphanReconciler.Decision decision = OrphanReconciler.reconcile(detail(), existing, "source_missing", new Date());
        assertEquals(OrphanReconciler.Action.SKIP, decision.action());
    }
}
