package com.ruoyi.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ruoyi.common.core.domain.Result;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.openliststrm.mybatisplus.domain.PtTorrentBlacklistPlus;
import com.ruoyi.openliststrm.mybatisplus.service.IPtTorrentBlacklistPlusService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PT 种子/发布组手动黑名单 REST API 控制器。
 * <p>
 * 管理页新增/编辑只支持 {@code RELEASE_GROUP} 类型——{@code GUID} 类型的规则只能通过
 * {@link PtDownloadRecordRestController#blacklistGuid} 由后端直接从已知的
 * {@code guidHash} 生成，不经过用户手填。这条限制由 Service 层
 * （{@code PtTorrentBlacklistPlusServiceImpl.save()/updateById()}）强制执行，
 * 本控制器负责把该校验异常转成前端能看懂的错误信息。
 * </p>
 *
 * @author Jack
 */
@RestController
@RequestMapping("/api/openliststrm/pt-torrent-blacklists")
public class PtTorrentBlacklistRestController extends BaseCrudRestController<IPtTorrentBlacklistPlusService, PtTorrentBlacklistPlus> {

    @Override
    protected Wrapper<PtTorrentBlacklistPlus> buildQueryWrapper(PtTorrentBlacklistPlus entity) {
        LambdaQueryWrapper<PtTorrentBlacklistPlus> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(entity.getType())) {
            wrapper.eq(PtTorrentBlacklistPlus::getType, entity.getType());
        }
        if (StringUtils.isNotBlank(entity.getDisplayValue())) {
            wrapper.like(PtTorrentBlacklistPlus::getDisplayValue, entity.getDisplayValue());
        }
        wrapper.orderByDesc(PtTorrentBlacklistPlus::getId);
        return wrapper;
    }

    @Override
    @PostMapping
    public Result<Void> add(@RequestBody PtTorrentBlacklistPlus entity) {
        try {
            return super.add(entity);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    @Override
    @PutMapping
    public Result<Void> edit(@RequestBody PtTorrentBlacklistPlus entity) {
        try {
            return super.edit(entity);
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }
}
