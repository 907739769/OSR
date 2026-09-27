package com.osr.common.core.domain.event;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 参数配置（sys_config）发生变更后发布的事件。
 * <p>
 * 由 {@code SysConfigServiceImpl} 在增、改、删、重置缓存之后发布，业务模块用
 * {@code @EventListener} 订阅，实现「改完配置不用重启」。事件放在 osr-common，
 * 是为了让 osr-system 只管发布、不必反向依赖业务模块。
 * <p>
 * 注意：事件在写库的调用线程里同步分发，监听方不要在里面做慢操作（网络请求等），
 * 需要的话自己转异步。
 *
 * @param keys 变更的参数键；为空集合表示「全部可能变化」（重置缓存时）
 * @author Jack
 */
public record SysConfigChangedEvent(Set<String> keys) {

    public SysConfigChangedEvent {
        keys = keys == null ? Set.of() : keys.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableSet());
    }

    /** 重置缓存等无法确定具体键的场景 */
    public static SysConfigChangedEvent all() {
        return new SysConfigChangedEvent(Set.of());
    }

    /** 是否可能影响某一前缀下的参数；「全部」事件对任何前缀都返回 true */
    public boolean affectsPrefix(String prefix) {
        return keys.isEmpty() || keys.stream().anyMatch(k -> k.startsWith(prefix));
    }
}
