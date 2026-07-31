package com.osr.openliststrm.pt.downloader;

import com.osr.openliststrm.mybatisplus.domain.PtDownloaderPlus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按 pt_downloader.type 分发到具体的下载器实现。
 * 新增下载器类型只需实现 IDownloaderClient 并注册为 Bean，本类无需改动。
 *
 * @author Jack
 */
@Component
public class DownloaderClientFactory {

    private final Map<String, IDownloaderClient> clients;

    public DownloaderClientFactory(List<IDownloaderClient> clientList) {
        this.clients = clientList.stream()
                .collect(Collectors.toMap(IDownloaderClient::type, Function.identity()));
    }

    /**
     * @throws IllegalArgumentException 配置的类型没有对应实现
     */
    public IDownloaderClient get(PtDownloaderPlus config) {
        IDownloaderClient client = clients.get(config.getType());
        if (client == null) {
            throw new IllegalArgumentException("不支持的下载器类型：" + config.getType());
        }
        return client;
    }
}
