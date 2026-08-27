package com.hjmicro.fluent.entity;

import cn.org.atool.fluent.mybatis.annotation.FluentMybatis;
import cn.org.atool.fluent.mybatis.annotation.GmtCreate;
import cn.org.atool.fluent.mybatis.annotation.GmtModified;
import cn.org.atool.fluent.mybatis.annotation.LogicDelete;
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
 * TaskExecutionEntity: 数据映射实体定义
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
    table = "task_execution",
    schema = "auto_test"
)
public class TaskExecutionEntity extends RichEntity {
  private static final long serialVersionUID = 1L;

  @TableId(
      value = "id",
      desc = "主键ID"
  )
  private Long id;

  @TableField(
      value = "end_time",
      desc = "结束时间"
  )
  private Date endTime;

  @TableField(
      value = "execute_status",
      desc = "执行状态1.已下发 2.执行中 3.执行结束"
  )
  private String executeStatus;

  @TableField(
      value = "execute_time",
      desc = "分钟单位"
  )
  private Integer executeTime;

  @TableField(
      value = "ip_address",
      desc = "IP地址"
  )
  private String ipAddress;

  @TableField(
      value = "logs",
      desc = "执行日志"
  )
  private String logs;

  @TableField(
      value = "machine_tag",
      desc = "机器标签"
  )
  private String machineTag;

  @TableField(
      value = "dispatch_token",
      desc = "任务下发令牌"
  )
  private String dispatchToken;

  @TableField(
      value = "dispatch_base_agent_session_id",
      desc = "下发时agent会话"
  )
  private String dispatchBaseAgentSessionId;

  @TableField(
      value = "dispatch_base_state_version",
      desc = "下发时agent状态版本"
  )
  private Long dispatchBaseStateVersion;

  @TableField(
      value = "dispatch_time",
      desc = "下发时间"
  )
  private Date dispatchTime;

  @TableField(
      value = "result",
      desc = "返回值"
  )
  private String result;

  @TableField(
      value = "start_time",
      desc = "开始时间"
  )
  private Date startTime;

  @TableField(
      value = "status",
      desc = "状态"
  )
  private String status;

  @TableField(
      value = "task_id",
      desc = "任务id"
  )
  private Long taskId;

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

  @TableField(
      value = "is_deleted",
      insert = "0",
      desc = "是否删除"
  )
  @LogicDelete
  private Boolean isDeleted;

  @Override
  public final Class entityClass() {
    return TaskExecutionEntity.class;
  }
}
