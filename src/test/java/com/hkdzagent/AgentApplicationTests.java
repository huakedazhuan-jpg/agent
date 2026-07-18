package com.hkdzagent;

import com.hkdzagent.agent.AgentApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = AgentApplication.class,
        properties = {
                "spring.ai.openai.api-key=test-openai-key",
                "feishu.app-id=test-feishu-app-id",
                "feishu.app-secret=test-feishu-app-secret",
                "tavily.api-key=test-tavily-key"
        }
)
class AgentApplicationTests {

    @Test
    void contextLoads() {
    }

}
