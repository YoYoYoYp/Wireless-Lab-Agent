package com.njupt.wirelesslabagent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_APPLICATION_CONTEXT_TEST", matches = "true")
class WirelessLabAgentApplicationTests {

    @Test
    void contextLoads() {
    }
}
