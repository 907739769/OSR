package com.osr.openliststrm.helper;

import com.osr.common.core.domain.entity.SysDictData;
import com.osr.system.service.ISysDictDataService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
@Slf4j
public class SysDictDataHelper {

    @Autowired
    private ISysDictDataService dictDataService;

    private final ConcurrentHashMap<String, List<String>> cache = new ConcurrentHashMap<>();

    /** 字典值的小写去点集合缓存，用于扩展名等高频后缀匹配，避免热路径反复 toLowerCase */
    private final ConcurrentHashMap<String, Set<String>> lowerSetCache = new ConcurrentHashMap<>();

    public List<String> getAllValue(String dictType) {
        if (dictType == null) {
            return Collections.emptyList();
        }
        return cache.computeIfAbsent(dictType, this::loadDictValues);
    }

    /**
     * 返回字典值的小写集合（去掉前导点）。适用于扩展名匹配等高频场景，
     * 结果被缓存，避免每次调用都对字典值做 toLowerCase。
     */
    public Set<String> getAllValueLowerSet(String dictType) {
        if (dictType == null) {
            return Collections.emptySet();
        }
        return lowerSetCache.computeIfAbsent(dictType, dt -> getAllValue(dt).stream()
                .filter(Objects::nonNull)
                .map(v -> {
                    String s = v.trim().toLowerCase();
                    return s.startsWith(".") ? s.substring(1) : s;
                })
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet()));
    }

    public void refreshCache(String dictType) {
        if (dictType == null) return;
        cache.remove(dictType);
        lowerSetCache.remove(dictType);
        log.debug("Refreshed cache for dictType: {}", dictType);
    }

    private List<String> loadDictValues(String dictType) {
        try {
            SysDictData data = new SysDictData();
            data.setDictType(dictType);
            List<SysDictData> dictDataList = dictDataService.selectDictDataList(data);
            List<String> values = dictDataList.stream()
                    .map(SysDictData::getDictValue)
                    .collect(Collectors.toList());
            log.debug("Loaded dictType={}, {} values", dictType, values.size());
            return values;
        } catch (Exception e) {
            log.error("Failed to load dictType: {}", dictType, e);
            return Collections.emptyList();
        }
    }

    @PreDestroy
    public void destroy() {
        cache.clear();
        lowerSetCache.clear();
    }
}
