package com.osr.openliststrm.controller.api;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.osr.common.core.domain.Result;
import com.osr.common.core.domain.entity.SysUser;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.WecomUserPlus;
import com.osr.openliststrm.mybatisplus.service.IWecomUserPlusService;
import com.osr.system.service.ISysUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 企业微信成员绑定管理 REST API。
 * <p>
 * 绑定关系决定了「企微里发指令的这个人是 OSR 的谁」以及「这条通知该推给谁」，
 * 所以新增/修改都要校验企微 UserId 唯一、OSR 用户真实存在，不能让一条指向不存在用户的
 * 绑定悄悄落库——那样企微侧会一直提示未绑定，后台看起来却明明绑好了。
 *
 * @author Jack
 * @date 2026-08-05
 */
@Slf4j
@RestController
@RequestMapping("/api/openliststrm/wecom-users")
public class WecomUserRestController extends BaseCrudRestController<IWecomUserPlusService, WecomUserPlus> {

    @Autowired
    private ISysUserService sysUserService;

    @Override
    protected Wrapper<WecomUserPlus> buildQueryWrapper(WecomUserPlus entity) {
        LambdaQueryWrapper<WecomUserPlus> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.isNotBlank(entity.getWecomUserid())) {
            wrapper.like(WecomUserPlus::getWecomUserid, entity.getWecomUserid());
        }
        if (StringUtils.isNotBlank(entity.getSysUserName())) {
            wrapper.like(WecomUserPlus::getSysUserName, entity.getSysUserName());
        }
        if (StringUtils.isNotBlank(entity.getStatus())) {
            wrapper.eq(WecomUserPlus::getStatus, entity.getStatus());
        }
        return wrapper.orderByDesc(WecomUserPlus::getId);
    }

    /**
     * 可绑定的 OSR 用户下拉数据。前端建绑定时选人用，只回 id 和名字，不回密码等字段。
     */
    @GetMapping("/selectable-users")
    public Result<List<SelectableUser>> selectableUsers() {
        List<SysUser> users = sysUserService.selectUserList(new SysUser());
        return Result.success(users.stream()
                .map(u -> new SelectableUser(u.getUserId(), u.getLoginName(), u.getUserName()))
                .toList());
    }

    @Override
    @PostMapping
    public Result<Void> add(@RequestBody WecomUserPlus entity) {
        String error = validateAndFill(entity);
        if (error != null) {
            return Result.error(error);
        }
        return super.add(entity);
    }

    @Override
    @PutMapping
    public Result<Void> edit(@RequestBody WecomUserPlus entity) {
        String error = validateAndFill(entity);
        if (error != null) {
            return Result.error(error);
        }
        return super.edit(entity);
    }

    /**
     * 校验入参并回填冗余的登录名。
     *
     * @return 校验失败的原因，通过则返回 null
     */
    private String validateAndFill(WecomUserPlus entity) {
        String wecomUserId = StringUtils.trim(entity.getWecomUserid());
        if (StringUtils.isBlank(wecomUserId)) {
            return "企业微信 UserId 不能为空";
        }
        entity.setWecomUserid(wecomUserId);
        if (entity.getSysUserId() == null) {
            return "请选择要绑定的 OSR 用户";
        }
        SysUser user = sysUserService.selectUserById(entity.getSysUserId());
        if (user == null) {
            return "所选 OSR 用户不存在";
        }
        entity.setSysUserName(user.getLoginName());

        // 唯一性前置校验：库上有唯一索引兜底，但直接撞索引只会抛出一条含 SQL 的 500，
        // 这里先给出「已被谁占用」的可读提示。编辑自己时要排除自身，否则改备注都会被拦下
        WecomUserPlus existing = service.getByWecomUserId(wecomUserId);
        if (existing != null && !existing.getId().equals(entity.getId())) {
            return "企业微信 UserId [" + wecomUserId + "] 已绑定到用户 " + existing.getSysUserName();
        }
        if (StringUtils.isBlank(entity.getStatus())) {
            entity.setStatus("0");
        }
        return null;
    }

    /**
     * 用户下拉项。
     *
     * @param userId    OSR 用户ID
     * @param loginName 登录名
     * @param userName  昵称
     */
    public record SelectableUser(Long userId, String loginName, String userName) {
    }
}
