package com.osr.openliststrm.mybatisplus.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.osr.openliststrm.mybatisplus.domain.WecomUserPlus;

import java.util.List;

/**
 * <p>
 * 企业微信成员绑定 服务类
 * </p>
 *
 * @author Jack
 * @since 2026-08-05
 */
public interface IWecomUserPlusService extends IService<WecomUserPlus> {

    /**
     * 按企微 UserId 查绑定关系（含已停用的，调用方自行判断 {@code isEnabled()}）。
     * 企微 UserId 上有唯一索引，至多一条。
     *
     * @return 未绑定时返回 null
     */
    WecomUserPlus getByWecomUserId(String wecomUserId);

    /**
     * 按 OSR 用户ID 查其全部<b>启用中</b>的企微绑定。
     * 允许一个 OSR 账号绑多个企微号（同一人有多个企微身份），所以返回列表。
     *
     * @return 无绑定时返回空列表，不返回 null
     */
    List<WecomUserPlus> listEnabledBySysUserId(Long sysUserId);
}
