package com.hkdzagent.agent.model;

public record ChatRequest(
        String message,    // 用户发送的具体内容
        String sessionId   // 会话 ID（比如飞书的用户 ID，用来隔离不同人的聊天记录，为下一步做记忆做准备）
) {}
