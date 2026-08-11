package com.osr.openliststrm.config;

import com.osr.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * @Author Jack
 * @Date 2025/7/16 19:32
 * @Version 1.0.0
 */
@Component
public class OpenlistConfig {

    @Autowired
    private ISysConfigService sysConfigService;

    //	openlist-访问地址
    public String getOpenListUrl() {
        return sysConfigService.selectConfigByKey("openlist.server.url");
    }

    //openlist-api访问token
    public String getOpenListToken() {
        return sysConfigService.selectConfigByKey("openlist.server.token");
    }

    //复制的最小文件
    public String getOpenListMinFileSize() {
        return sysConfigService.selectConfigByKey("openlist.copy.minfilesize");
    }

    //复制完文件生成strm
    public String getOpenListCopyStrm() {
        return sysConfigService.selectConfigByKey("openlist.copy.strm");
    }

    //tg机器人token
    public String getOpenListTgToken() {
        return sysConfigService.selectConfigByKey("openlist.tg.token");
    }

    //tg用户id
    public String getOpenListTgUserId() {
        return sysConfigService.selectConfigByKey("openlist.tg.userid");
    }

    //通知Webhook地址
    public String getNotifyWebhookUrl() {
        return sysConfigService.selectConfigByKey("openlist.notify.webhook.url");
    }

    //Telegram通知类型过滤，逗号分隔的NotificationType名称，留空=不过滤，全部类型都发
    public String getNotifyTgTypes() {
        return sysConfigService.selectConfigByKey("openlist.notify.tg.types");
    }

    //Webhook通知类型过滤，逗号分隔的NotificationType名称，留空=不过滤，全部类型都发
    public String getNotifyWebhookTypes() {
        return sysConfigService.selectConfigByKey("openlist.notify.webhook.types");
    }

    //企业微信-企业ID(corpid)
    public String getWeComCorpId() {
        return sysConfigService.selectConfigByKey("openlist.wecom.corpid");
    }

    //企业微信-自建应用AgentId
    public String getWeComAgentId() {
        return sysConfigService.selectConfigByKey("openlist.wecom.agentid");
    }

    //企业微信-自建应用Secret
    public String getWeComSecret() {
        return sysConfigService.selectConfigByKey("openlist.wecom.secret");
    }

    //企业微信-回调Token(签名校验用)
    public String getWeComToken() {
        return sysConfigService.selectConfigByKey("openlist.wecom.token");
    }

    //企业微信-回调EncodingAESKey(报文加解密用，43位)
    public String getWeComAesKey() {
        return sysConfigService.selectConfigByKey("openlist.wecom.aeskey");
    }

    /**
     * 企业微信-无归属通知的默认接收人，多个用 | 分隔。
     * 未配置时返回 {@code @all}（应用可见范围内全部成员），与建表脚本的默认值保持一致：
     * 配置项被人为清空后若返回空串，企微接口会直接报 40008，通知静默全丢。
     */
    public String getWeComToUser() {
        String value = sysConfigService.selectConfigByKey("openlist.wecom.touser");
        return (value != null && !value.isBlank()) ? value.trim() : "@all";
    }

    /**
     * 企业微信-API 代理地址（中转）。
     * <p>
     * 2022-06-20 之后创建的自建应用调用企微 API 必须登记「企业可信IP」，家宽/动态 IP
     * 的部署登记不了，通行做法是反代 qyapi.weixin.qq.com 后把中转地址填在这里。
     * 未配置时返回官方地址——不使用代理是绝大多数情况，不该让用户必须填点什么。
     * 非法值的兜底在 {@code WeComApiClient#resolveApiBase}，那里还要负责拼 /cgi-bin/。
     */
    public String getWeComProxyUrl() {
        String value = sysConfigService.selectConfigByKey("openlist.wecom.proxy");
        return (value != null && !value.isBlank()) ? value.trim() : "https://qyapi.weixin.qq.com";
    }

    /**
     * 企业微信-是否在成员首次发指令时自动建 OSR 账号并绑定。
     * 未配置时默认<b>开启</b>：绝大多数使用者只在企微里用，不会登网页端，
     * 要求管理员逐个预先建号本末倒置。关掉则回到「先建绑定才能用」的审批制。
     */
    public boolean isWeComAutoCreateUser() {
        String value = sysConfigService.selectConfigByKey("openlist.wecom.autocreate");
        return value == null || value.isBlank() || "1".equals(value.trim());
    }

    //企业微信通知类型过滤，逗号分隔的NotificationType名称，留空=不过滤，全部类型都发
    public String getNotifyWeComTypes() {
        return sysConfigService.selectConfigByKey("openlist.notify.wecom.types");
    }

    //Apikey
    public String getOpenListApiKey() {
        return sysConfigService.selectConfigByKey("openlist.api.apikey");
    }

    // TMDb API Key (stored in sys_config as 'openlist.tmdb.apikey')
    public String getTmdbApiKey() {
        return sysConfigService.selectConfigByKey("openlist.tmdb.apikey");
    }

    // OpenAI API Key (stored in sys_config as 'openlist.openai.apikey')
    public String getOpenAiApiKey() {
        return sysConfigService.selectConfigByKey("openlist.openai.apikey");
    }

    // OpenAI service endpoint / host. If configured, this can be a full URL (including scheme and path)
    // or just a host/domain like 'api.chatanywhere.tech'. If empty, clients should default to OpenAI's endpoint.
    public String getOpenAiEndpoint() {
        return sysConfigService.selectConfigByKey("openlist.openai.endpoint");
    }

    // OpenAI model name (stored in sys_config as 'openlist.openai.model'). If empty, clients should use a sensible default.
    public String getOpenAiModel() {
        return sysConfigService.selectConfigByKey("openlist.openai.model");
    }

    // STRM输出目录 (默认: /data/strm)
    public String getOpenListStrmOutputDir() {
        String value = sysConfigService.selectConfigByKey("openlist.strm.outputdir");
        return (value != null && !value.isBlank()) ? value : "/data/strm";
    }

    // STRM路径编码开关 (默认: 0-不编码)
    public String getOpenListStrmEncode() {
        String value = sysConfigService.selectConfigByKey("openlist.strm.encode");
        return (value != null && !value.isBlank()) ? value : "0";
    }

    // STRM下载字幕开关 (默认: 0-不下载)
    public String getOpenListStrmDownloadSub() {
        String value = sysConfigService.selectConfigByKey("openlist.strm.downloadsub");
        return (value != null && !value.isBlank()) ? value : "0";
    }

    // API refresh开关 (默认: 1-开启)
    public String getOpenListApiRefresh() {
        String value = sysConfigService.selectConfigByKey("openlist.api.refresh");
        return (value != null && !value.isBlank()) ? value : "1";
    }

    /**
     * 目录遍历（STRM 生成 / 同步时的目标目录存在性列举）时是否强制 AList 刷新网盘。
     * 遍历会对整棵目录树逐目录 fs/list，若每次都 refresh 会强制网盘重新扫描，对网络盘非常慢。
     * 默认 false（走 AList 缓存，大幅加速）。需要遍历时立即感知新增文件的用户可置为 1 开启。
     * 注意：源目录同步列举不受此开关影响，始终按 {@link #getOpenListApiRefresh()} 以保证增量正确性。
     */
    public boolean getTraversalRefresh() {
        String value = sysConfigService.selectConfigByKey("openlist.api.traversal.refresh");
        if (value == null || value.isBlank()) {
            return false;
        }
        return "1".equals(value.trim());
    }

    // TMDb图片语言偏好 (默认: zh)
    public String getTmdbImageLanguage() {
        String value = sysConfigService.selectConfigByKey("openlist.tmdb.image.language");
        return (value != null && !value.isBlank()) ? value : "zh";
    }

    // TMDb元数据（标题、简介等）请求语言 (默认: zh-CN)
    public String getTmdbMetadataLanguage() {
        String value = sysConfigService.selectConfigByKey("openlist.tmdb.metadata.language");
        return (value != null && !value.isBlank()) ? value : "zh-CN";
    }

    // TMDb下载图片的尺寸 (默认: original；可选 w780/w500/w342 等以节省带宽和存储，见 TMDb 官方图片尺寸文档)
    public String getTmdbImageSize() {
        String value = sysConfigService.selectConfigByKey("openlist.tmdb.image.size");
        return (value != null && !value.isBlank()) ? value : "original";
    }

    /**
     * 获取最小文件大小（字节）。
     * 配置项存储的是 MB 值，此方法将其转换为字节。
     * 默认值：1 MB
     */
    public long getMinFileSizeBytes() {
        try {
            return Long.parseLong(getOpenListMinFileSize()) * 1024 * 1024;
        } catch (Exception e) {
            return 1L * 1024 * 1024;
        }
    }

    /**
     * 本地目录浏览接口允许访问的根目录白名单（逗号分隔）。
     * 未配置时默认仅允许挂载的 /data 目录，避免管理端接口可以枚举整个宿主机文件系统。
     */
    public List<String> getAllowedLocalRoots() {
        String value = sysConfigService.selectConfigByKey("openlist.local.allowedroots");
        if (value == null || value.isBlank()) {
            return List.of("/data");
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 目录遍历（STRM 生成 / 文件同步）的并发度。遍历时每个目录一次 fs/list 网络请求，
     * 并发列举可显著缩短大目录树的遍历时间。未配置或非法时默认 10，上限 64 以免压垮 AList。
     */
    public int getTraversalConcurrency() {
        String value = sysConfigService.selectConfigByKey("openlist.api.traversal.concurrency");
        if (value == null || value.isBlank()) {
            return 10;
        }
        try {
            int n = Integer.parseInt(value.trim());
            if (n < 1) return 1;
            return Math.min(n, 64);
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    /**
     * 同步遍历时要跳过的「临时目录」识别规则：逗号分隔的正则，<b>整体匹配目录名</b>（不是子串匹配）。
     * <p>
     * 默认一条 {@code .+__[0-9A-Za-z]{6}}，对应下载器删种时产生的
     * {@code <原目录名>__<6位随机字符>} 临时目录（见 {@code OpenListHelper#isTransientDir}）。
     * 未配置或留空时用默认值；填 {@code off}（不区分大小写）关闭整个过滤。
     */
    public String getCopyTransientDirPatterns() {
        String value = sysConfigService.selectConfigByKey("openlist.copy.transientdirs");
        return (value != null && !value.isBlank()) ? value.trim() : ".+__[0-9A-Za-z]{6}";
    }

    /**
     * 复制任务状态监控的最长持续时间（分钟）。超过该时长仍未结束的任务会被强制标记为异常，
     * 停止继续调度，避免下游长期卡在非终态时，调度任务无限期堆积。
     * 未配置或配置非法时默认 600 分钟。
     */
    public long getCopyMonitorMaxMinutes() {
        String value = sysConfigService.selectConfigByKey("openlist.copy.monitor.maxminutes");
        if (value == null || value.isBlank()) {
            return 600L;
        }
        try {
            long minutes = Long.parseLong(value.trim());
            return minutes > 0 ? minutes : 600L;
        } catch (NumberFormatException e) {
            return 600L;
        }
    }

}
