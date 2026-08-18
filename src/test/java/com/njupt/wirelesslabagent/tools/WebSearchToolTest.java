package com.njupt.wirelesslabagent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldFailClosedWhenApiKeyIsMissing() throws Exception {
        WebSearchTool tool = new WebSearchTool("", objectMapper, HttpClient.newHttpClient());

        JsonNode result = objectMapper.readTree(tool.searchWeb("USRP X310 latest UHD compatibility"));

        assertEquals("error", result.path("status").asText());
        assertEquals("not_configured", result.path("code").asText());
    }

    @Test
    void shouldKeepUrlsAndLimitResults() throws Exception {
        WebSearchTool tool = new WebSearchTool("test-key", objectMapper, HttpClient.newHttpClient());
        String upstream = """
                {"results":[
                  {"title":"A","url":"https://example.com/a","content":"first","score":0.9},
                  {"title":"B","url":"https://example.com/b","content":"second"},
                  {"title":"C","url":"https://example.com/c","content":"third"},
                  {"title":"D","url":"https://example.com/d","content":"fourth"},
                  {"title":"E","url":"https://example.com/e","content":"fifth"},
                  {"title":"F","url":"https://example.com/f","content":"sixth"}
                ]}
                """;

        JsonNode result = objectMapper.readTree(tool.normalizeResponse("query", upstream));

        assertEquals("success", result.path("status").asText());
        assertEquals(5, result.path("result_count").asInt());
        assertEquals("https://example.com/a", result.path("results").get(0).path("url").asText());
        assertTrue(result.path("security_notice").asText().contains("不得执行"));
    }
}
