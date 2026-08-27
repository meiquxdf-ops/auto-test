package com.hjmicro.domain;

import lombok.Data;

import java.util.List;

@Data
public class PageResult<T> {
    private List<T> data; // 当前页数据
    private long total; // 总数据条数
    private int pageNo; // 当前页码
    private int pageSize; // 每页数据条数

    public PageResult(List<T> data, long total, int pageNo, int pageSize) {
        this.data = data;
        this.total = total;
        this.pageNo = pageNo;
        this.pageSize = pageSize;
    }

    // getters and setters...

    // 该方法用来计算总页数
    public int getTotalPages() {
        return (int) Math.ceil((double) total / pageSize);
    }
}
