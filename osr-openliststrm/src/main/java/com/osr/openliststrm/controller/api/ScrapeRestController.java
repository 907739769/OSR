package com.osr.openliststrm.controller.api;

import com.osr.common.core.controller.BaseController;
import com.osr.common.core.domain.Result;
import com.osr.common.utils.StringUtils;
import com.osr.framework.manager.AsyncManager;
import com.osr.openliststrm.mybatisplus.domain.RenameDetailPlus;
import com.osr.openliststrm.mybatisplus.service.IRenameDetailPlusService;
import com.osr.openliststrm.rename.RenameClientProvider;
import com.osr.openliststrm.rename.model.MediaInfo;
import com.osr.openliststrm.rename.MediaParser;
import com.osr.openliststrm.scrape.ScrapeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 刮削操作 REST API控制器
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/rename-details")
public class ScrapeRestController extends BaseController
{
    @Autowired
    private IRenameDetailPlusService renameDetailPlusService;

    @Autowired
    private ScrapeService scrapeService;

    @Autowired
    private RenameClientProvider clientProvider;

    /**
     * 重新刮削单条记录（默认全部刮削：NFO+图片）
     */
    @PostMapping("/scrape/{id}")
    public Result<Void> scrape(@PathVariable("id") Integer id)
    {
        if (id == null)
        {
            return Result.error("ID 为空");
        }
        RenameDetailPlus detail = renameDetailPlusService.getById(id);
        if (detail == null)
        {
            return Result.error("重命名明细不存在");
        }
        logger.info("开始重新刮削（全部），ID：{}", id);
        final int detailId = id;
        final String newName = detail.getNewName();
        final String mediaType = detail.getMediaType();

        AsyncManager.me().execute(() -> {
            try {
                MediaParser parser = new MediaParser(clientProvider.tmdb(), clientProvider.openAI());
                String tmdbId = detail.getTmdbId();
                MediaInfo info = StringUtils.isNotEmpty(tmdbId)
                        ? parser.parseWithKnownTmdbId(newName, tmdbId, mediaType)
                        : parser.parse(newName);

                Path destFile = Paths.get(detail.getNewPath(), newName);
                Path outputDir = destFile.getParent();

                scrapeService.scrapeAsync(
                        detailId, info, mediaType, destFile, outputDir,
                        "1", "1", "1", true
                );

                logger.info("刮削任务已启动，ID：{}", id);
            } catch (Exception e) {
                logger.error("刮削失败，ID：{}", id, e);
            }
        });
        return Result.success();
    }

    /**
     * 批量重新刮削
     */
    @PostMapping("/scrape")
    public Result<Void> batchScrape(@RequestParam("ids") String ids)
    {
        if (ids == null || ids.trim().isEmpty())
        {
            return Result.error("请选择要刮削的记录");
        }
        List<Integer> idList = parseIds(ids);
        if (idList.isEmpty())
        {
            return Result.error("未解析到有效的记录 ID");
        }
        for (Integer id : idList)
        {
            logger.info("开始批量刮削，ID：{}", id);
            final int detailId = id;
            AsyncManager.me().execute(() -> {
                try {
                    RenameDetailPlus detail = renameDetailPlusService.getById(detailId);
                    if (detail != null) {
                        scrape(detail.getId());
                    }
                } catch (Exception e) {
                    logger.error("批量刮削失败，ID：{}", detailId, e);
                }
            });
        }
        return Result.success();
    }

    /**
     * 删除单条记录的刮削文件（NFO + 图片）
     */
    @PostMapping("/scrape/delete/{id}")
    public Result<Void> deleteScrapeFiles(@PathVariable("id") Integer id)
    {
        if (id == null)
        {
            return Result.error("ID 为空");
        }
        RenameDetailPlus detail = renameDetailPlusService.getById(id);
        if (detail == null)
        {
            return Result.error("重命名明细不存在");
        }
        int deleted = scrapeService.deleteScrapeFiles(detail);
        logger.info("删除刮削文件完成，ID：{}，删除数量：{}", id, deleted);
        return Result.success();
    }

    /**
     * 批量删除刮削文件
     */
    @PostMapping("/scrape/batch")
    public Result<Void> batchDeleteScrapeFiles(@RequestParam("ids") String ids)
    {
        if (ids == null || ids.trim().isEmpty())
        {
            return Result.error("请选择要删除刮削文件的记录");
        }
        List<Integer> idList = parseIds(ids);
        if (idList.isEmpty())
        {
            return Result.error("未解析到有效的记录 ID");
        }
        int totalDeleted = 0;
        for (Integer id : idList)
        {
            RenameDetailPlus detail = renameDetailPlusService.getById(id);
            if (detail != null)
            {
                totalDeleted += scrapeService.deleteScrapeFiles(detail);
            }
        }
        logger.info("批量删除刮削文件完成，记录数：{}，删除文件数：{}", idList.size(), totalDeleted);
        return Result.success();
    }

    /**
     * 解析逗号分隔的 ID 列表，跳过非法（非数字）片段并记录警告，而不是直接抛异常导致整个请求 500。
     */
    private List<Integer> parseIds(String ids)
    {
        List<Integer> result = new ArrayList<>();
        for (String s : ids.split(","))
        {
            s = s.trim();
            if (s.isEmpty())
            {
                continue;
            }
            try
            {
                result.add(Integer.parseInt(s));
            }
            catch (NumberFormatException e)
            {
                logger.warn("忽略非法的记录 ID：{}", s);
            }
        }
        return result;
    }
}
