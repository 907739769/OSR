package com.osr.openliststrm.mybatisplus.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.osr.common.mybatisplus.BaseEntity;

import lombok.Getter;
import lombok.Setter;

/**
 * <p>
 * 热门自动订阅执行日志，记录每轮拉取后每个候选条目的处理结果，供排查"这轮为什么没加/加了什么"。
 * </p>
 *
 * @author Jack
 * @since 2026-07-29
 */
@Getter
@Setter
@TableName("pt_auto_add_log")
public class PtAutoAddLogPlus extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /** 规则 ID */
    @TableField("rule_id")
    private Integer ruleId;

    /** TMDb ID，转换失败时可能为空 */
    @TableField("tmdb_id")
    private String tmdbId;

    /** 来源侧条目标识（豆瓣 subject id），TMDb 源为空 */
    @TableField("source_item_id")
    private String sourceItemId;

    /** 来源侧条目链接（豆瓣条目页），TMDb 源为空 */
    @TableField("source_item_url")
    private String sourceItemUrl;

    /** 媒体类型 TV/MOVIE */
    @TableField("media_type")
    private String mediaType;

    /** 标题 */
    @TableField("title")
    private String title;

    /** 订阅的季号，电影为空 */
    @TableField("season")
    private Integer season;

    /** 处理结果 ADDED / SKIPPED_EXISTS / SKIPPED_FILTER / SKIPPED_NO_MATCH / FAILED */
    @TableField("result")
    private String result;

    /** 附加说明，如跳过原因、失败异常信息 */
    @TableField("message")
    private String message;
}
