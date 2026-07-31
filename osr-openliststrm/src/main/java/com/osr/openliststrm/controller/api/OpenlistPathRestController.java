package com.osr.openliststrm.controller.api;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.api.OpenlistApi;
import com.osr.openliststrm.config.OpenlistConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 路径选择 REST API控制器
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/path")
public class OpenlistPathRestController extends BaseController
{
    private static final Logger log = LoggerFactory.getLogger(OpenlistPathRestController.class);

    @Autowired
    private OpenlistApi openlistApi;

    @Autowired
    private OpenlistConfig openlistConfig;

    /**
     * 获取 Openlist 目录结构
     */
    @GetMapping("/openlist")
    public Result<List<Map<String, Object>>> getOpenlistPath(@RequestParam(value = "id", required = false) String id)
    {
        List<Map<String, Object>> trees = new ArrayList<>();
        String path = StringUtils.isEmpty(id) ? "/" : id;
        log.info("正在获取 Openlist 目录: {}", path);

        try
        {
            JSONObject response = openlistApi.getOpenlist(path);
            if (response != null && Integer.valueOf(200).equals(response.getInteger("code")))
            {
                JSONObject data = response.getJSONObject("data");
                JSONArray content = data.getJSONArray("content");

                if (content != null)
                {
                    for (int i = 0; i < content.size(); i++)
                    {
                        JSONObject file = content.getJSONObject(i);
                        if (file.getBoolean("is_dir"))
                        {
                            Map<String, Object> map = new HashMap<>();
                            String name = file.getString("name");
                            String currentPath = "/".equals(path) ? "/" + name : path + "/" + name;

                            map.put("id", currentPath);
                            map.put("pId", path);
                            map.put("name", name);
                            map.put("title", name);
                            map.put("isParent", true);
                            trees.add(map);
                        }
                    }
                }
            }
            else
            {
                log.error("Openlist API 返回错误: {}", response);
            }
        }
        catch (Exception e)
        {
            log.error("获取 Openlist 目录异常", e);
        }
        return Result.success(trees);
    }

    /**
     * 获取本地目录结构
     */
    @GetMapping("/local")
    public Result<List<Map<String, Object>>> getLocalPath(@RequestParam(value = "id", required = false) String id)
    {
        List<Map<String, Object>> trees = new ArrayList<>();
        log.info("正在获取本地目录，父节点 ID: {}", id);

        boolean isRoot = StringUtils.isEmpty(id);
        List<String> allowedRoots = openlistConfig.getAllowedLocalRoots();

        try
        {
            if (isRoot)
            {
                // 根节点：仅返回配置的白名单根目录，不再枚举整个宿主机的磁盘/挂载点，
                // 避免管理端接口被用来遍历服务器上任意目录
                for (String rootPath : allowedRoots)
                {
                    try
                    {
                        File root = new File(rootPath);
                        if (root.exists() && root.isDirectory())
                        {
                            String currentPath = root.getCanonicalPath().replace("\\", "/");
                            Map<String, Object> map = new HashMap<>();
                            map.put("id", currentPath);
                            map.put("pId", "");
                            map.put("name", currentPath);
                            map.put("title", currentPath);
                            map.put("isParent", true);
                            trees.add(map);
                        }
                        else
                        {
                            log.warn("配置的本地根目录不存在，已跳过: {}", rootPath);
                        }
                    }
                    catch (Exception e)
                    {
                        // 单个根目录规范化失败不应影响其余白名单根目录的展示
                        log.warn("规范化本地根目录失败，已跳过: {}", rootPath, e);
                    }
                }
                return Result.success(trees);
            }

            File parent = new File(id);
            if (!parent.exists())
            {
                log.warn("目录不存在: {}", id);
                return Result.success(trees);
            }
            if (!isPathAllowed(parent, allowedRoots))
            {
                log.warn("拒绝访问白名单之外的本地路径: {}", id);
                return Result.error("该路径不在允许访问的范围内");
            }

            File[] files = parent.listFiles();
            if (files != null)
            {
                for (File file : files)
                {
                    if (file.isDirectory() && !file.isHidden())
                    {
                        String currentPath = file.getAbsolutePath().replace("\\", "/");
                        Map<String, Object> map = new HashMap<>();
                        map.put("id", currentPath);
                        map.put("pId", id);
                        map.put("name", file.getName());
                        map.put("title", file.getPath());
                        map.put("isParent", true);
                        trees.add(map);
                    }
                }
            }
            else
            {
                log.warn("无法列出目录内容 (可能是权限问题): {}", id);
            }
        }
        catch (Exception e)
        {
            log.error("获取本地目录失败", e);
        }

        return Result.success(trees);
    }

    /**
     * 校验目标路径是否落在允许访问的根目录白名单内。
     * 按规范化后的路径做包含关系判断（Path#startsWith），而非简单的字符串前缀比较，
     * 避免 "/data-other" 这类同前缀但实际不同目录的绕过，也避免 ".." 路径穿越。
     */
    private boolean isPathAllowed(File target, List<String> allowedRoots)
    {
        try
        {
            Path targetPath = target.getCanonicalFile().toPath();
            for (String rootStr : allowedRoots)
            {
                Path rootPath = new File(rootStr).getCanonicalFile().toPath();
                if (targetPath.equals(rootPath) || targetPath.startsWith(rootPath))
                {
                    return true;
                }
            }
        }
        catch (Exception e)
        {
            log.warn("路径规范化校验失败: {}", target, e);
        }
        return false;
    }
}
