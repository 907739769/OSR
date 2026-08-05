package com.osr.openliststrm.wecom;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 菜单结构的合法性。
 * <p>
 * 这些约束违反了不会在本地报错，只会在点「同步应用菜单」时被企微拒掉，
 * 而错误码（40054 之类）看不出到底是哪一项超了，所以在这里提前挡住。
 */
class WeComMenuDefinitionTest {

    /** 企微限制：最多 3 个一级菜单 */
    private static final int MAX_TOP = 3;
    /** 每个一级下最多 5 个二级 */
    private static final int MAX_SUB = 5;
    /** 一级菜单名 ≤ 16 字节 */
    private static final int MAX_TOP_NAME_BYTES = 16;
    /** 二级菜单名 ≤ 40 字节 */
    private static final int MAX_SUB_NAME_BYTES = 40;

    private static int bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    private static JSONArray topButtons() {
        return WeComMenuDefinition.build().getJSONArray("button");
    }

    @Test
    void 一级菜单不超过3个() {
        assertTrue(topButtons().size() <= MAX_TOP,
                "一级菜单最多 " + MAX_TOP + " 个，实际 " + topButtons().size());
    }

    @Test
    void 每个一级下的二级不超过5个() {
        JSONArray tops = topButtons();
        for (int i = 0; i < tops.size(); i++) {
            JSONArray sub = tops.getJSONObject(i).getJSONArray("sub_button");
            assertTrue(sub != null && sub.size() <= MAX_SUB,
                    tops.getJSONObject(i).getString("name") + " 的二级菜单超过 " + MAX_SUB + " 个");
        }
    }

    @Test
    void 菜单名长度都在企微限制内() {
        JSONArray tops = topButtons();
        for (int i = 0; i < tops.size(); i++) {
            JSONObject top = tops.getJSONObject(i);
            String topName = top.getString("name");
            assertTrue(bytes(topName) <= MAX_TOP_NAME_BYTES,
                    "一级菜单名超长：" + topName + " (" + bytes(topName) + " 字节)");
            JSONArray sub = top.getJSONArray("sub_button");
            for (int j = 0; j < sub.size(); j++) {
                String subName = sub.getJSONObject(j).getString("name");
                assertTrue(bytes(subName) <= MAX_SUB_NAME_BYTES,
                        "二级菜单名超长：" + subName + " (" + bytes(subName) + " 字节)");
            }
        }
    }

    /**
     * 菜单 key 必须都能在指令白名单里查到，否则用户点了只会收到「该菜单项已失效」。
     * 菜单结构和白名单分处两个文件，最容易改一边忘另一边。
     */
    @Test
    void 每个菜单key都有对应的指令() {
        JSONArray tops = topButtons();
        for (int i = 0; i < tops.size(); i++) {
            JSONArray sub = tops.getJSONObject(i).getJSONArray("sub_button");
            for (int j = 0; j < sub.size(); j++) {
                JSONObject button = sub.getJSONObject(j);
                String key = button.getString("key");
                assertTrue(WeComCommandService.MENU_COMMANDS.containsKey(key),
                        "菜单「" + button.getString("name") + "」的 key [" + key
                                + "] 不在 WeComCommandService.MENU_COMMANDS 白名单里");
            }
        }
    }

    /** 全部用 click：view 跳转对登不进网页端的影子账号没有意义 */
    @Test
    void 菜单项都是click类型() {
        JSONArray tops = topButtons();
        for (int i = 0; i < tops.size(); i++) {
            JSONArray sub = tops.getJSONObject(i).getJSONArray("sub_button");
            for (int j = 0; j < sub.size(); j++) {
                assertEquals("click", sub.getJSONObject(j).getString("type"));
            }
        }
    }

    /** 一级菜单只作分组，自己不该带 key/type，否则企微会认为它可点 */
    @Test
    void 一级菜单只作分组不带key() {
        JSONArray tops = topButtons();
        for (int i = 0; i < tops.size(); i++) {
            JSONObject top = tops.getJSONObject(i);
            assertFalse(top.containsKey("key"), top.getString("name") + " 不该带 key");
            assertFalse(top.containsKey("type"), top.getString("name") + " 不该带 type");
        }
    }
}
