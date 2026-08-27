package com.hjmicro.fluent.entity;

import cn.org.atool.fluent.mybatis.annotation.FluentMybatis;
import cn.org.atool.fluent.mybatis.annotation.GmtCreate;
import cn.org.atool.fluent.mybatis.annotation.GmtModified;
import cn.org.atool.fluent.mybatis.annotation.TableField;
import cn.org.atool.fluent.mybatis.annotation.TableId;
import cn.org.atool.fluent.mybatis.base.RichEntity;
import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * HeartbeatEntity: 数据映射实体定义
 *
 * @author Powered By Fluent Mybatis
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@Data
@Accessors(
    chain = true
)
@EqualsAndHashCode(
    callSuper = false
)
@AllArgsConstructor
@NoArgsConstructor
@FluentMybatis(
    table = "heartbeat",
    schema = "auto_test"
)
public class HeartbeatEntity extends RichEntity {
  private static final long serialVersionUID = 1L;

  @TableId(
      value = "id",
      desc = "主键ID"
  )
  private Integer id;

  @TableField(
      value = "available_memory",
      desc = "可用内存"
  )
  private Long availableMemory;

  @TableField(
      value = "cpu_usage",
      desc = "CPU使用率"
  )
  private Double cpuUsage;

  @TableField(
      value = "disk_usage",
      desc = "硬盘使用情况"
  )
  private String diskUsage;

  @TableField(
      value = "ip_address",
      desc = "IP地址"
  )
  private String ipAddress;

  @TableField(
      value = "memory",
      desc = "内存"
  )
  private String memory;

  @TableField(
      value = "network_bandwidth",
      desc = "网络带宽"
  )
  private Double networkBandwidth;

  @TableField(
      value = "operating_system",
      desc = "操作系统"
  )
  private String operatingSystem;

  @TableField(
      value = "physical_location",
      desc = "物理位置"
  )
  private String physicalLocation;

  @TableField(
      value = "processor",
      desc = "处理器"
  )
  private String processor;

  @TableField(
      value = "status",
      desc = "机器状态"
  )
  private String status;

  @TableField(
      value = "total_memory",
      desc = "总内存"
  )
  private Long totalMemory;

  @TableField(
      value = "gmt_created",
      insert = "now()",
      desc = "创建时间"
  )
  @GmtCreate
  private Date gmtCreated;

  @TableField(
      value = "gmt_modified",
      insert = "now()",
      update = "now()",
      desc = "最后更新时间"
  )
  @GmtModified
  private Date gmtModified;

  @Override
  public final Class entityClass() {
    return HeartbeatEntity.class;
  }
}
