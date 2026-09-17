package com.osr.openliststrm.helper;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.osr.openliststrm.mybatisplus.domain.OpenlistCopyPlus;
import com.osr.openliststrm.mybatisplus.domain.OpenlistStrmPlus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.nio.file.NoSuchFileException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class CopyFailReasonTest {

    @Test
    void 任务失败_带上OpenList给的error() {
        JSONObject info = JSONObject.of("code", 200, "data", JSONObject.of("state", 7, "error", " failed to put: quota exceeded "));

        assertEquals("OpenList 复制任务失败：failed to put: quota exceeded", CopyFailReason.taskFailed(info));
    }

    @Test
    void 任务失败_没有error时明说未返回原因而不是留空() {
        assertEquals("OpenList 复制任务失败（未返回具体原因）",
                CopyFailReason.taskFailed(JSONObject.of("code", 200, "data", JSONObject.of("state", 7))));
        assertEquals("OpenList 复制任务失败（未返回具体原因）", CopyFailReason.taskFailed(null));
    }

    @Test
    void 提交失败_区分OpenList报错与无响应() {
        assertEquals("提交复制任务失败：storage not found",
                CopyFailReason.submitFailed(JSONObject.of("code", 500, "message", "storage not found")));
        assertEquals("提交复制任务失败（OpenList 无响应）", CopyFailReason.submitFailed(null));
    }

    @Test
    void 超长原因截断到列宽() {
        String error = "x".repeat(2000);
        String reason = CopyFailReason.taskFailed(JSONObject.of("data", JSONObject.of("error", error)));

        assertEquals(CopyFailReason.MAX_LENGTH, reason.length());
        assertTrue(reason.endsWith("…"));
    }

    @Test
    void 监控超时写出分钟数() {
        assertTrue(CopyFailReason.monitorTimeout(Duration.ofMinutes(180)).startsWith("超过 180 分钟"));
    }

    @Test
    void STRM失败原因_带异常类名_没有message时退回类名() {
        assertEquals("写入 .strm 文件失败：NoSuchFileException: /data/strm/a.strm",
                StrmHelper.failReason("写入 .strm 文件失败", new NoSuchFileException("/data/strm/a.strm")));
        assertEquals("下载字幕失败：NullPointerException",
                StrmHelper.failReason("下载字幕失败", new NullPointerException()));
    }

    /**
     * fail_reason 必须是 ALWAYS 更新策略：默认的 NOT_NULL 会把 null 跳过，一条失败后重跑成功的记录
     * 经 updateById 写成成功时旧原因会原样留在库里。这个行为只在真实 SQL 里体现，单测 mock 掉 service
     * 看不到，所以直接检查 MyBatis-Plus 生成的 SET 片段：ALWAYS 不会被 {@code <if test>} 包着。
     * 反过来 file_size 必须保持默认策略，否则单文件 STRM 生成（拿不到大小）会把目录级采集到的值抹成 null。
     */
    @Test
    void 失败原因总是写入_文件大小为null时不覆盖() {
        for (Class<?> entity : new Class<?>[]{OpenlistCopyPlus.class, OpenlistStrmPlus.class}) {
            TableInfo info = tableInfo(entity);

            assertFalse(column(info, "fail_reason").getSqlSet("et.").contains("<if"),
                    entity.getSimpleName() + ".fail_reason 必须无条件写入");
            assertTrue(column(info, "file_size").getSqlSet("et.").contains("<if"),
                    entity.getSimpleName() + ".file_size 为 null 时不应覆盖");
        }
    }

    private static TableInfo tableInfo(Class<?> entity) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace(entity.getName());
        return TableInfoHelper.initTableInfo(assistant, entity);
    }

    private static TableFieldInfo column(TableInfo info, String column) {
        return info.getFieldList().stream()
                .filter(f -> f.getColumn().equals(column))
                .findFirst()
                .orElseThrow(() -> new AssertionError("找不到列 " + column));
    }
}
