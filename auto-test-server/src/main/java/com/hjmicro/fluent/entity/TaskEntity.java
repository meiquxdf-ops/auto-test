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
 * TaskEntity: 数据映射实体定义
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
    table = "task",
    schema = "auto_test"
)
public class TaskEntity extends RichEntity {
  private static final long serialVersionUID = 1L;

  @TableId(
      value = "id",
      desc = "主键ID"
  )
  private Long id;

  @TableField("annex")
  private String annex;

  @TableField(
      value = "command",
      desc = "执行命令"
  )
  private String command;

  @TableField("condition_config")
  private String conditionConfig;

  @TableField(
      value = "desc",
      desc = "任务描述"
  )
  private String desc;

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
      value = "machine_ips",
      desc = "机器ip"
  )
  private String machineIps;

  @TableField(
      value = "machine_tag",
      desc = "机器标识"
  )
  private String machineTag;

  @TableField(
      value = "message",
      desc = "日志信息"
  )
  private String message;

  @TableField(
      value = "name",
      desc = "任务名称"
  )
  private String name;

  @TableField("operator")
  private String operator;

  @TableField(
      value = "request_id",
      desc = "唯一id"
  )
  private String requestId;

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

  @TableField("success_condition_type")
  private String successConditionType;

  @TableField(
      value = "weight",
      desc = "权重"
  )
  private Integer weight;

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
    return TaskEntity.class;
  }
}
