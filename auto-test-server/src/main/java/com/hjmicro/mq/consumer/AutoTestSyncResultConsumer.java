package com.hjmicro.mq.consumer;

import com.hjmicro.service.TaskService;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson.JSONObject;

import java.util.concurrent.TimeUnit;

@Service
@RocketMQMessageListener(
    consumerGroup = AutoTestSyncResultConsumer.GROUP,
    topic = AutoTestSyncResultConsumer.TOPIC,
    consumeMode = ConsumeMode.CONCURRENTLY
)
public class AutoTestSyncResultConsumer implements RocketMQListener<MessageExt> {

    // requestId 标准格式 AUTO_TEST_ + 计划的id
    public static final String TOPIC = "DEV_EXEC_TASK_TOPIC";

    public static final String GROUP = "DEV_EXEC_TASK_GROUP";

    @Autowired
    private RedisTemplate redisTemplate;


    @Autowired
    private TaskService taskService;


    @Override
    public void onMessage(MessageExt messageExt) {
        try {
            // 获取 RocketMQ 消息的 MessageId
            String messageId = messageExt.getMsgId();
            System.out.println("收到消息，MessageId: " + messageId);
            // 使用 Redis 的 SETNX 实现原子去重
            Boolean isUnique = redisTemplate.opsForValue().setIfAbsent(messageId, "processed", 1, TimeUnit.HOURS);

            if (!isUnique) {
                System.out.println("消息重复，已忽略。MessageId: " + messageId);
                return;
            }
            // 解析消息内容
            String body = new String(messageExt.getBody(), "UTF-8");
            JSONObject jsonObject = JSONObject.parseObject(body);

            System.out.println("消息内容：" + jsonObject.toJSONString());

            // 执行业务逻辑
            taskService.doTask(jsonObject);

            System.out.println("消息处理完成，MessageId: " + messageId);

        } catch (Exception e) {
            System.err.println("消息处理失败，发生异常：" + e.getMessage());
            e.printStackTrace();
        }
    }




}
