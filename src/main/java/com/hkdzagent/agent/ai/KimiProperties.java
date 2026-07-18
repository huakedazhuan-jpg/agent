package com.hkdzagent.agent.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "agent.kimi")
public class KimiProperties {

    private Duration requestTimeout = Duration.ofSeconds(60);
    private int maxToolRounds = 5;
    private int historyLimit = 20;

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int maxToolRounds() {
        return maxToolRounds;
    }

    public int getMaxToolRounds() {
        return maxToolRounds;
    }

    public void setMaxToolRounds(int maxToolRounds) {
        this.maxToolRounds = maxToolRounds;
    }

    public int historyLimit() {
        return historyLimit;
    }

    public int getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(int historyLimit) {
        this.historyLimit = historyLimit;
    }
}
