package com.osr.web.controller.api;

import com.osr.common.annotation.Anonymous;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 存活探针。给 Docker healthcheck / 反代 / 外部监控用，不给前端页面用。
 * <p>
 * 返回值刻意不套 {@code Result}：探针看的是 HTTP 状态码，健康 200、不健康 503，
 * 而 {@code Result} 无论成败都是 200，探针永远判不出问题。
 * </p>
 * <p>
 * 只探数据库：应用能应答说明 JVM 和 Web 容器活着，而这个系统所有功能——配置、任务、订阅——
 * 都落在 MySQL 上，连不上库时进程虽然还在，实际已经不可用了。
 * 外部依赖（OpenList / TMDb / 下载器 / 索引器）不探：它们不可用是业务问题，
 * 不该让容器被判定为不健康而重启。
 * </p>
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/health")
public class HealthApiController {

    private static final Logger log = LoggerFactory.getLogger(HealthApiController.class);

    /** 校验连接可用性的超时（秒），要短于探针自身的 timeout */
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;

    private final DataSource dataSource;

    public HealthApiController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Anonymous
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        boolean dbUp = checkDatabase();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dbUp ? "UP" : "DOWN");
        body.put("db", dbUp ? "UP" : "DOWN");
        return dbUp
                ? ResponseEntity.ok(body)
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean checkDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (Exception e) {
            // 具体异常只进日志：这是个匿名端点，响应体不回显连接串、账号等细节
            log.warn("[health] 数据库探测失败: {}", e.getMessage());
            return false;
        }
    }
}
