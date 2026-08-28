package com.atest;

import static org.assertj.core.api.Assertions.assertThat;

import com.atest.config.AtestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.unit.DataSize;

/**
 * 附件的生产默认值不许漂移：单文件上限 32MB 必须同时钉死在应用层
 * （atest.attachments.max-bytes）与容器层（spring.servlet.multipart）。
 * AttachmentHttpTest 为了避开 32MB 夹具覆盖了 max-bytes，这里单独用未覆盖的上下文断言。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-attach-defaults;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0"
})
class AttachmentDefaultsTest {

    @Autowired
    AtestProperties props;

    @Autowired
    MultipartProperties multipartProperties;

    @Test
    void singleFileHardLimitIs32Mb() {
        assertThat(props.getAttachments().getMaxBytes()).isEqualTo(33554432L);
        assertThat(multipartProperties.getMaxFileSize()).isEqualTo(DataSize.ofMegabytes(32));
        // 整包上限要容得下恰好 32MB 的文件 + multipart 边界开销
        assertThat(multipartProperties.getMaxRequestSize().toBytes())
                .isGreaterThanOrEqualTo(DataSize.ofMegabytes(32).toBytes());
    }

    @Test
    void uploadPathStaysBounded() {
        assertThat(props.getAttachments().getMaxConcurrent()).isEqualTo(8);
        assertThat(props.getAttachments().getQueueCapacity()).isGreaterThan(0);
        assertThat(props.getHttp().getPublicBase()).isEqualTo("http://127.0.0.1:8080");
    }
}
