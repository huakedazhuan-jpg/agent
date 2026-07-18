package com.hkdzagent.agent.controller;

import com.hkdzagent.agent.ai.LLMClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class TestController {

    private final LLMClient llmClient;

    public TestController(LLMClient llmClient) {
        this.llmClient = llmClient;
    }

    /**
     * 一次性返回完整回答。
     * 示例：http://localhost:8080/test/chat?msg=你好
     */
    @GetMapping("/test/chat")
    public String chat(@RequestParam String msg) {
        return llmClient.askSimple(msg);
    }

    /**
     * 通过 SSE 流式返回回答。
     * 示例：http://localhost:8080/test/stream?msg=给我写一个100字的故事
     */
    @GetMapping(value = "/test/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=UTF-8")
    public Flux<String> stream(@RequestParam String msg) {
        return llmClient.askStream(msg);
    }
}
