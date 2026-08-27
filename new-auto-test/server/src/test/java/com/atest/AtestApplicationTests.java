package com.atest;

import static org.assertj.core.api.Assertions.assertThat;

import com.atest.tcp.AgentTcpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:atest-context;DB_CLOSE_DELAY=-1",
        "atest.agent.port=0"
})
class AtestApplicationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AgentTcpServer tcpServer;

    @Test
    void contextLoadsAndFlywayCreatedEveryTable() {
        for (String table : new String[]{"agent", "task", "task_execution", "execution_log",
                "agent_event", "dispatch_event"}) {
            Integer count = jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
            assertThat(count).as("table %s", table).isNotNull();
        }
        assertThat(tcpServer.boundPort()).isPositive();
    }
}
