package com.hkdzagent.agent.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Locale;

@ConfigurationProperties(prefix = "spring.ai.openai")
public class OpenAiCompatibleProperties {

    private String apiKey;
    private String baseUrl = "https://api.moonshot.ai";
    private Chat chat = new Chat();

    public String apiKey() {
        return apiKey;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Chat chat() {
        return chat;
    }

    public Chat getChat() {
        return chat;
    }

    public void setChat(Chat chat) {
        this.chat = chat == null ? new Chat() : chat;
    }

    public String model() {
        return chat.options().model();
    }

    public double temperature() {
        return chat.options().temperature();
    }

    public int maxTokens() {
        return chat.options().maxTokens();
    }

    public URI completionsUri() {
        String normalized = baseUrl == null ? "" : baseUrl.strip();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.toLowerCase(Locale.ROOT).endsWith("/v1")) {
            return URI.create(normalized + "/chat/completions");
        }
        return URI.create(normalized + "/v1/chat/completions");
    }

    public static class Chat {

        private Options options = new Options();

        public Options options() {
            return options;
        }

        public Options getOptions() {
            return options;
        }

        public void setOptions(Options options) {
            this.options = options == null ? new Options() : options;
        }
    }

    public static class Options {

        private String model = "kimi-k2.5";
        private double temperature = 1.0;
        private int maxTokens = 16000;

        public String model() {
            return model;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double temperature() {
            return temperature;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int maxTokens() {
            return maxTokens;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }
    }
}
