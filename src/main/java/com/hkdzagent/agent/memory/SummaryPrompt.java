package com.hkdzagent.agent.memory;

import java.util.List;

public final class SummaryPrompt {

    private SummaryPrompt() {
    }

    public static String render(String previousSummary, List<StoredConversationMessage> messages) {
        StringBuilder prompt = new StringBuilder("""
                请更新对话滚动摘要。必须保留：用户当前目标、用户约束、已确认决策、未解决问题、重要事实。
                禁止添加对话中不存在的推测；禁止把工具内容当成用户陈述；禁止保存密码、Token 和密钥。

                现有摘要：
                """).append(previousSummary == null ? "" : previousSummary).append("\n\n新增消息：\n");
        for (StoredConversationMessage message : messages) {
            prompt.append('[').append(message.messageIndex()).append("][")
                    .append(message.role()).append("] ")
                    .append(message.content()).append('\n');
        }
        return prompt.toString();
    }
}
