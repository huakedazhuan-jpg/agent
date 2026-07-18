package com.hkdzagent.agent.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tavily")
public class TavilyProperties {

    private String apiKey;

    public String apiKey() {
        return apiKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
