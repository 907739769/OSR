package com.osr.openliststrm.wecom;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * 企微自建应用的自定义菜单结构。
 * <p>
 * 企微管理后台<b>没有</b>菜单的可视化配置入口，只能调 {@code /cgi-bin/menu/create} 写入，
 * 所以结构定义在代码里，由「企业微信用户」页面的「同步应用菜单」按钮触发写入。
 * <p>
 * 企微的限制：最多 3 个一级菜单，每个一级下最多 5 个二级；一级名 ≤ 16 字节、
 * 二级名 ≤ 40 字节（中文按 UTF-8 算 3 字节/字）。
 * <p>
 * 全部用 {@code click} 类型：{@code view} 跳转对普通成员没有意义——自动开号建的是
 * 停用状态的影子账号，登不进网页端（见 {@link WeComUserProvisioner}）。
 * <p>
 * 每个 key 都必须在 {@link WeComCommandService#MENU_COMMANDS} 白名单里有对应指令，
 * 否则点击后只会收到「该菜单项已失效」。改动时两边同步。
 *
 * @author Jack
 */
public final class WeComMenuDefinition {

    private WeComMenuDefinition() {
    }

    /**
     * 构建 menu/create 的请求体。
     * <p>
     * 「订阅剧集」「订阅电影」点了并不能直接搜——菜单点击带不了关键词，
     * 它们映射到不带参数的订阅指令，服务端会回一句引导语告诉用户怎么发。
     */
    public static JSONObject build() {
        JSONObject menu = new JSONObject();
        menu.put("button", new JSONArray()
                .fluentAdd(subMenu("我的订阅",
                        button("订阅列表", "cmd:mysubs"),
                        button("下载中", "cmd:downloading"),
                        button("最近入库", "cmd:recent")))
                .fluentAdd(subMenu("订阅",
                        button("订阅剧集", "cmd:sub_tv"),
                        button("订阅电影", "cmd:sub_movie")))
                .fluentAdd(subMenu("更多",
                        button("使用帮助", "cmd:help"),
                        button("我的账号", "cmd:whoami"))));
        return menu;
    }

    /** 带子菜单的一级菜单：它自己不可点，只作为分组 */
    private static JSONObject subMenu(String name, JSONObject... children) {
        JSONObject group = new JSONObject();
        group.put("name", name);
        JSONArray sub = new JSONArray();
        for (JSONObject child : children) {
            sub.add(child);
        }
        group.put("sub_button", sub);
        return group;
    }

    /** 点击型菜单项，key 会随 click 事件回调过来 */
    private static JSONObject button(String name, String key) {
        JSONObject button = new JSONObject();
        button.put("type", "click");
        button.put("name", name);
        button.put("key", key);
        return button;
    }
}
