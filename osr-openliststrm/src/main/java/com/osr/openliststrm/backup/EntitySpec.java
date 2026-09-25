package com.osr.openliststrm.backup;

import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 一个「按业务键合并」的实体分区怎么备份、怎么恢复。
 *
 * @param section  分区
 * @param service  数据层
 * @param codec    库表列读写
 * @param excluded 不进备份的列：运行时状态（上次轮询时间、连续失败次数……），恢复到另一台机器上毫无意义，
 *                 带过去反而会让新机器以为那个索引器刚失败过五次
 * @param secrets  敏感列：导出时默认置空；恢复时为空表示「备份里没带」，保留目标机器上的现值
 * @param refs     引用了别的表 id 的列，备份里改存名字，恢复时再按名字换回本机的 id
 * @param key      业务键：备份里的一行与库里哪一行是「同一个东西」。取值于<b>备份形态</b>（引用已换成名字）
 * @param display  提示里怎么称呼这一行
 * @author Jack
 */
record EntitySpec<T>(BackupSection section, IService<T> service, EntityCodec<T> codec,
                     Set<String> excluded, Set<String> secrets, List<Ref> refs,
                     Function<JSONObject, String> key, Function<JSONObject, String> display) {

    /** 引用的是哪类东西 */
    enum RefKind {
        /** PT 下载器，按名称对应 */
        DOWNLOADER,
        /** OSR 用户，按登录名对应（不同机器上同一个人的 user_id 未必相同） */
        USER
    }

    /**
     * 一个引用列。
     *
     * @param idProperty   实体上存 id 的属性
     * @param nameProperty 备份里存名字的键，<b>不能与实体上的任何属性重名</b>，否则恢复时会被当成列写回去
     * @param kind         引用的是哪类东西
     * @param required     找不到时是跳过这一行（true），还是置空后照样恢复（false，表示「用默认」）
     */
    record Ref(String idProperty, String nameProperty, RefKind kind, boolean required) {
    }
}
