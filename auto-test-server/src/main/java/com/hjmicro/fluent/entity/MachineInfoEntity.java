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
 * MachineInfoEntity: 数据映射实体定义
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
    table = "machine_info",
    schema = "auto_test"
)
public class MachineInfoEntity extends RichEntity {
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
      value = "container_id",
      desc = "容器id"
  )
  private String containerId;

  @TableField(
      value = "container_name",
      desc = "容器名称"
  )
  private String containerName;

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

  @TableField("execute_status")
  private String executeStatus;

  @TableField(
      value = "ip_address",
      desc = "IP地址"
  )
  private String ipAddress;

  @TableField(
      value = "is_docker",
      desc = "是否是docker"
  )
  private Integer isDocker;

  @TableField(
      value = "last_updated",
      desc = "数据更新时间"
  )
  private Date lastUpdated;

  @TableField(
      value = "link_ip",
      desc = "链接ip"
  )
  private String linkIp;

  @TableField(
      value = "agent_session_id",
      desc = "agent会话"
  )
  private String agentSessionId;

  @TableField(
      value = "agent_state_version",
      desc = "agent状态版本"
  )
  private Long agentStateVersion;

  @TableField(
      value = "running_execute_id",
      desc = "agent当前执行ID"
  )
  private Long runningExecuteId;

  @TableField(
      value = "running_dispatch_token",
      desc = "agent当前执行令牌"
  )
  private String runningDispatchToken;

  @TableField(
      value = "active_execute_id",
      desc = "server当前下发执行ID"
  )
  private Long activeExecuteId;

  @TableField(
      value = "active_dispatch_token",
      desc = "server当前下发令牌"
  )
  private String activeDispatchToken;

  @TableField(
      value = "link_port",
      desc = "链接端口"
  )
  private String linkPort;

  @TableField(
      value = "machine_tag",
      desc = "机器标识"
  )
  private String machineTag;

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
      value = "tag",
      desc = "机器标签"
  )
  private String tag;

  @TableField(
      value = "task_id",
      desc = "执行id"
  )
  private Long taskId;

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
    return MachineInfoEntity.class;
  }
}
