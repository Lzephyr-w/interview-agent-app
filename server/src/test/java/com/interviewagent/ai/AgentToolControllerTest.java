package com.interviewagent.ai;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "SUPABASE_URL=https://example.supabase.co",
    "app.agent.internal-key=secret",
    "spring.datasource.url=jdbc:h2:mem:agent-tools-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.flyway.default-schema=PUBLIC",
    "spring.flyway.schemas=PUBLIC",
    "spring.flyway.create-schemas=false"
})
@AutoConfigureMockMvc
class AgentToolControllerTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcClient jdbc;

    @Test
    void rejectsRequestsWithoutInternalSecret() throws Exception {
        mockMvc.perform(post("/internal/agent/tools").contentType("application/json").content("{\"userId\":\"user-a\",\"name\":\"list_resources\",\"arguments\":{\"resource_type\":\"training_task\"}}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void cannotReadAnotherUsersTrainingTask() throws Exception {
        jdbc.sql("INSERT INTO training_tasks (id,user_id,title,weakness_tag,action,status) VALUES ('private-task','user-b','私有任务','系统设计','仅 user-b 可见','NOT_STARTED')").update();
        mockMvc.perform(post("/internal/agent/tools").header("X-Agent-Key", "secret").contentType("application/json")
                .content("{\"userId\":\"user-a\",\"name\":\"get_resource\",\"arguments\":{\"resource_type\":\"training_task\",\"id\":\"private-task\"}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.error").value("资源不存在或无权访问。"));
    }

    @Test
    void createsTrainingTaskIdempotently() throws Exception {
        String body = "{\"userId\":\"user-a\",\"name\":\"create_training_task\",\"arguments\":{\"title\":\"练习幂等\",\"weakness_tag\":\"系统设计\",\"action\":\"完成一次容量估算\"}}";
        mockMvc.perform(post("/internal/agent/tools").header("X-Agent-Key", "secret").contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("created"));
        mockMvc.perform(post("/internal/agent/tools").header("X-Agent-Key", "secret").contentType("application/json").content(body))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("already_exists"));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM training_tasks WHERE user_id='user-a' AND title='练习幂等'").query(Integer.class).single());
    }
}
