package com.osr.openliststrm.req;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * @Author Jack
 * @Date 2025/7/20 19:11
 * @Version 1.0.0
 */
@Data
public class NotifyReq {

    //源目录
    @NotBlank(message = "源目录不能为空")
    private String srcDir;
    //目标目录
    @NotBlank(message = "目标目录不能为空")
    private String srcDst;
    //qb下载器的下载根目录
    @NotBlank(message = "qb下载器的下载根目录不能为空")
    private String qbDlRootPath;
    //qb下载器的下载资源存放路径
    @NotBlank(message = "qb下载器的下载资源存放路径不能为空")
    private String qbDlFilePath;

}
