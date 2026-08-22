package com.osr.openliststrm.api;

import com.alibaba.fastjson2.JSONObject;
import com.osr.common.utils.Threads;
import com.osr.openliststrm.config.OpenlistConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class OpenlistApi {

    @Autowired
    private OpenlistConfig config;

    @Autowired
    @Qualifier("sharedOkHttpClient")
    private OkHttpClient sharedClient;

    private OkHttpClient client;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");
    private static final int MAX_RETRY = 3;
    private static final long RETRY_INTERVAL_MS = 1000L;

    @PostConstruct
    public void init() {
        client = sharedClient.newBuilder()
                .connectTimeout(90, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .build();
        log.debug("OkHttpClient initialized successfully.");
    }

    private Headers buildHeaders() {
        return new Headers.Builder()
                .add("Content-Type", "application/json")
                .add("Accept", "application/json")
                .add("Authorization", config.getOpenListToken())
                .build();
    }

    private JSONObject executeJsonPost(String apiUrl, JSONObject requestBody, String logPrefix, boolean needRetry) {
        Headers headers = buildHeaders();
        Request request = new Request.Builder()
                .url(apiUrl)
                .headers(headers)
                .post(RequestBody.create(JSON_MEDIA_TYPE, requestBody.toJSONString()))
                .build();

        if (needRetry) {
            return executeWithRetry(request, logPrefix);
        }
        return executeOnce(request, logPrefix);
    }

    private JSONObject executeWithRetry(Request request, String logPrefix) {
        JSONObject jsonResponse = null;
        for (int i = 0; i < MAX_RETRY; i++) {
            boolean lastAttempt = i == MAX_RETRY - 1;
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    jsonResponse = JSONObject.parseObject(response.body().string());
                    if (jsonResponse != null && Integer.valueOf(200).equals(jsonResponse.getInteger("code"))) {
                        log.debug("{}成功", logPrefix);
                        return jsonResponse;
                    }
                    log.debug("Response Body: {}", jsonResponse != null ? jsonResponse.toJSONString() : "null");
                    log.warn("{}第{}次失败", logPrefix, i + 1);
                } else {
                    log.warn("{}第{}次失败，HTTP状态码: {}", logPrefix, i + 1, response.code());
                }
            } catch (Exception e) {
                log.error("{}第{}次异常", logPrefix, i + 1, e);
            }
            // 所有可重试路径（HTTP失败、业务失败、网络异常）统一走退避重试，避免无间隔连环重试
            if (!lastAttempt) {
                Threads.sleep(RETRY_INTERVAL_MS * (i + 1));
            }
        }
        return jsonResponse;
    }

    private JSONObject executeOnce(Request request, String logPrefix) {
        try (Response response = client.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                JSONObject jsonResponse = JSONObject.parseObject(response.body().string());
                log.debug("{}成功", logPrefix);
                return jsonResponse;
            }
            log.warn("Request failed with code: {}", response.code());
            log.error("Request failed with response: {}", response);
            return null;
        } catch (Exception e) {
            // 合成一条：原先「上下文」与「堆栈」分成两条 ERROR 打，sys-error.log 里各占一条，
            // 中间还可能被并发请求的日志隔开，翻到堆栈时看不出它属于哪次调用。
            log.error("{}失败", logPrefix, e);
            return null;
        }
    }

    public JSONObject getOpenlist(String path) {
        // 默认沿用全局 openlist.api.refresh 配置
        return getOpenlist(path, !"0".equals(config.getOpenListApiRefresh()));
    }

    /**
     * 列举目录。{@code refresh=true} 会强制 AList 重新扫描网盘（慢，但能立即看到新增文件），
     * {@code refresh=false} 走 AList 缓存（快，适合大目录树的并发遍历）。
     */
    public JSONObject getOpenlist(String path, boolean refresh) {
        log.debug("开始获取openlist目录{} refresh={}", path, refresh);
        JSONObject body = new JSONObject();
        body.put("path", path);
        body.put("password", "");
        body.put("page", 1);
        body.put("per_page", 0);
        body.put("refresh", refresh);
        return executeJsonPost(config.getOpenListUrl() + "/api/fs/list", body, "获取openlist目录" + path, true);
    }

    public JSONObject getFile(String path) {
        log.debug("开始获取openlist文件{}", path);
        JSONObject body = new JSONObject();
        body.put("path", path);
        body.put("password", "");
        body.put("page", 1);
        body.put("per_page", 0);
        body.put("refresh", !"0".equals(config.getOpenListApiRefresh()));
        return executeJsonPost(config.getOpenListUrl() + "/api/fs/get", body, "获取openlist文件" + path, false);
    }

    public JSONObject copyOpenlist(String srcDir, String dstDir, List<String> names) {
        String logPrefix = String.format("复制[%s]=>[%s]", srcDir + "/" + names.get(0), dstDir);
        log.debug(logPrefix);
        JSONObject body = new JSONObject();
        body.put("src_dir", srcDir);
        body.put("dst_dir", dstDir);
        body.put("names", names);
        return executeJsonPost(config.getOpenListUrl() + "/api/fs/copy", body, logPrefix, true);
    }

    public JSONObject mkdir(String path) {
        log.debug("开始创建openlist目录{}", path);
        JSONObject body = new JSONObject();
        body.put("path", path);
        JSONObject result = executeJsonPost(config.getOpenListUrl() + "/api/fs/mkdir", body, "创建openlist目录" + path, false);
        if (result != null && Integer.valueOf(200).equals(result.getInteger("code"))) {
            log.debug("创建openlist目录完成{}", path);
        }
        return result;
    }

    public JSONObject copyInfo(String tid) {
        Headers headers = new Headers.Builder()
                .add("Accept", "application/json")
                .add("Authorization", config.getOpenListToken())
                .build();
        RequestBody formBody = new FormBody.Builder().add("tid", tid).build();
        Request request = new Request.Builder()
                .url(config.getOpenListUrl() + "/api/task/copy/info" + "?tid=" + tid)
                .headers(headers)
                .post(formBody)
                .build();
        return executeOnce(request, "获取复制任务信息" + tid);
    }

    public JSONObject fsRemove(String dir, List<String> names) {
        log.debug("开始删除文件 dir={}, names={}", dir, names);
        JSONObject body = new JSONObject();
        body.put("dir", dir);
        body.put("names", names);
        return executeJsonPost(config.getOpenListUrl() + "/api/fs/remove", body, "删除文件" + dir, false);
    }

}
