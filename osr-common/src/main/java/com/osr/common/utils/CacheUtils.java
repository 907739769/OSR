package com.osr.common.utils;

import com.osr.common.utils.spring.SpringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Set;

/**
 * Cache工具类（Spring Cache）
 * 
 * @author osr
 */
public class CacheUtils
{
    private static Logger logger = LoggerFactory.getLogger(CacheUtils.class);

    private static CacheManager cacheManager = SpringUtils.getBean(CacheManager.class);

    private static final String SYS_CACHE = "sysCache";

    /**
     * 获取SYS_CACHE缓存
     * 
     * @param key
     * @return
     */
    public static Object get(String key)
    {
        return get(SYS_CACHE, key);
    }

    /**
     * 获取SYS_CACHE缓存
     * 
     * @param key
     * @param defaultValue
     * @return
     */
    public static Object get(String key, Object defaultValue)
    {
        Object value = get(key);
        return value != null ? value : defaultValue;
    }

    /**
     * 写入SYS_CACHE缓存
     * 
     * @param key
     * @return
     */
    public static void put(String key, Object value)
    {
        put(SYS_CACHE, key, value);
    }

    /**
     * 从SYS_CACHE缓存中移除
     * 
     * @param key
     * @return
     */
    public static void remove(String key)
    {
        remove(SYS_CACHE, key);
    }

    /**
     * 获取缓存
     * 
     * @param cacheName
     * @param key
     * @return
     */
    @SuppressWarnings("unchecked")
    public static Object get(String cacheName, String key)
    {
        Cache cache = getCache(cacheName);
        Cache.ValueWrapper wrapper = cache.get(key);
        return wrapper != null ? wrapper.get() : null;
    }

    /**
     * 获取缓存
     * 
     * @param cacheName
     * @param key
     * @param defaultValue
     * @return
     */
    public static Object get(String cacheName, String key, Object defaultValue)
    {
        Object value = get(cacheName, key);
        return value != null ? value : defaultValue;
    }

    /**
     * 写入缓存
     * 
     * @param cacheName
     * @param key
     * @param value
     */
    public static void put(String cacheName, String key, Object value)
    {
        getCache(cacheName).put(key, value);
    }

    /**
     * 从缓存中移除
     * 
     * @param cacheName
     * @param key
     */
    public static void remove(String cacheName, String key)
    {
        getCache(cacheName).evict(key);
    }

    /**
     * 从缓存中移除所有
     * 
     * @param cacheName
     */
    public static void removeAll(String cacheName)
    {
        Cache cache = getCache(cacheName);
        cache.clear();
        logger.info("清理缓存：{}", cacheName);
    }

    /**
     * 从缓存中移除指定key
     * 
     * @param keys
     */
    public static void removeByKeys(Set<String> keys)
    {
        removeByKeys(SYS_CACHE, keys);
    }

    /**
     * 从缓存中移除指定key
     * 
     * @param cacheName
     * @param keys
     */
    public static void removeByKeys(String cacheName, Set<String> keys)
    {
        for (String key : keys)
        {
            remove(key);
        }
        logger.info("清理缓存： {} => {}", cacheName, keys);
    }

    /**
     * 获取缓存键名
     * 
     * @param key
     * @return
     */
    private static String getKey(String key)
    {
        return key;
    }

    /**
     * 获得一个Cache，没有则显示日志。
     * 
     * @param cacheName
     * @return
     */
    public static Cache getCache(String cacheName)
    {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null)
        {
            throw new RuntimeException("当前系统中没有定义" + cacheName + "这个缓存。");
        }
        return cache;
    }
}
