package com.hkdzagent.agent.context;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.context")
public class ContextBudget {

    private int maxInputTokens = 12000;
    private int maxCurrentUserTokens = 4000;
    private int systemTokens = 1500;
    private int longTermMemoryTokens = 2000;
    private int summaryTokens = 1200;
    private int recentMessageTokens = 3500;
    private int toolObservationTokens = 1800;
    private int reservedProtocolTokens = 2000;

    public int getMaxInputTokens() { return maxInputTokens; }
    public void setMaxInputTokens(int value) { this.maxInputTokens = value; }
    public int getMaxCurrentUserTokens() { return maxCurrentUserTokens; }
    public void setMaxCurrentUserTokens(int value) { this.maxCurrentUserTokens = value; }
    public int getSystemTokens() { return systemTokens; }
    public void setSystemTokens(int value) { this.systemTokens = value; }
    public int getLongTermMemoryTokens() { return longTermMemoryTokens; }
    public void setLongTermMemoryTokens(int value) { this.longTermMemoryTokens = value; }
    public int getSummaryTokens() { return summaryTokens; }
    public void setSummaryTokens(int value) { this.summaryTokens = value; }
    public int getRecentMessageTokens() { return recentMessageTokens; }
    public void setRecentMessageTokens(int value) { this.recentMessageTokens = value; }
    public int getToolObservationTokens() { return toolObservationTokens; }
    public void setToolObservationTokens(int value) { this.toolObservationTokens = value; }
    public int getReservedProtocolTokens() { return reservedProtocolTokens; }
    public void setReservedProtocolTokens(int value) { this.reservedProtocolTokens = value; }

    public int usableTokens() {
        return Math.max(0, maxInputTokens - reservedProtocolTokens);
    }
}
