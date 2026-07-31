package com.osr.openliststrm.helper;

import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.notify.NotifierManager;

/**
 * 兼容门面：历史遗留的静态调用入口。{@link #sendMsg(String)} 现在会转发给
 * {@link NotifierManager}，由其分发到所有已启用的通知渠道（不再只是 Telegram）。
 * <p>
 * 之所以保留这个静态方法而不是让调用方直接注入 {@link NotifierManager}，是因为
 * {@code DownloadTrackServiceTest}、{@code SearchSupplementServiceTest}、
 * {@code RssPollServiceTest} 三个测试文件里共 14 处
 * {@code Mockito.mockStatic(TgHelper.class)} 断言依赖这个类名和方法签名不变。
 * <p>
 * 新代码不应该再调用这个静态方法——应该走标准的构造函数注入：注入 {@link NotifierManager}
 * 并调用其 {@code send(msg)}，这样新代码天然可测（不需要 {@code mockStatic}）。
 *
 * @Author Jack
 * @Date 2025/7/20 18:49
 * @Version 1.0.0
 */
public class TgHelper {

    public static void sendMsg(String msg) {
        SpringUtils.getBean(NotifierManager.class).send(msg);
    }

}
