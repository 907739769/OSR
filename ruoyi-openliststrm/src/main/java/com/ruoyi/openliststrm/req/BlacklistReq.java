package com.ruoyi.openliststrm.req;

import lombok.Data;

/**
 * 拉黑操作的可选请求体："拉黑该种子"/"拉黑该发布组" 两个端点共用。
 *
 * @author Jack
 */
@Data
public class BlacklistReq {

    /** 拉黑原因，可选；不填则使用 Service 层默认文案 */
    private String reason;
}
