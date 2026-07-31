package com.osr.common.core.domain;

import java.io.Serializable;
import java.util.List;

/**
 * 分页 API 响应包装
 */
public class PageResult<T> implements Serializable
{
    private static final long serialVersionUID = 1L;

    private List<T> records;
    private long total;
    private int page;
    private int size;

    public PageResult() {}

    public PageResult(List<T> records, long total, int page, int size)
    {
        this.records = records;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public static <T> PageResult<T> of(List<T> records, long total, int page, int size)
    {
        return new PageResult<>(records, total, page, size);
    }

    public List<T> getRecords()
    {
        return records;
    }

    public void setRecords(List<T> records)
    {
        this.records = records;
    }

    public long getTotal()
    {
        return total;
    }

    public void setTotal(long total)
    {
        this.total = total;
    }

    public int getPage()
    {
        return page;
    }

    public void setPage(int page)
    {
        this.page = page;
    }

    public int getSize()
    {
        return size;
    }

    public void setSize(int size)
    {
        this.size = size;
    }
}
