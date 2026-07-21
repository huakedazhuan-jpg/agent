package com.hkdzagent.agent.im;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "feishu")
public class FeishuProperties {

    private String appId;
    private String appSecret;
    private String verificationToken = "";
    private String encryptKey = "";
    private Async async = new Async();
    private Inbox inbox = new Inbox();
    private Outbox outbox = new Outbox();

    public String appId() {
        return appId;
    }

    public String getAppId() {
        return appId;
    }

    public void setAppId(String appId) {
        this.appId = appId;
    }

    public String appSecret() {
        return appSecret;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public String verificationToken() {
        return verificationToken;
    }

    public String getVerificationToken() {
        return verificationToken;
    }

    public void setVerificationToken(String verificationToken) {
        this.verificationToken = verificationToken;
    }

    public String encryptKey() {
        return encryptKey;
    }

    public String getEncryptKey() {
        return encryptKey;
    }

    public void setEncryptKey(String encryptKey) {
        this.encryptKey = encryptKey;
    }

    public Async async() {
        return async;
    }

    public Async getAsync() {
        return async;
    }

    public void setAsync(Async async) {
        this.async = async == null ? new Async() : async;
    }

    public Inbox inbox() {
        return inbox;
    }

    public Inbox getInbox() {
        return inbox;
    }

    public void setInbox(Inbox inbox) {
        this.inbox = inbox == null ? new Inbox() : inbox;
    }

    public Outbox outbox() {
        return outbox;
    }

    public Outbox getOutbox() {
        return outbox;
    }

    public void setOutbox(Outbox outbox) {
        this.outbox = outbox == null ? new Outbox() : outbox;
    }

    public static class Async {

        private int coreSize = 2;
        private int maxSize = 4;
        private int queueCapacity = 100;

        public int coreSize() {
            return coreSize;
        }

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int maxSize() {
            return maxSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int queueCapacity() {
            return queueCapacity;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    public static class Inbox {

        private String repository = "memory";
        private int maxAttempts = 3;
        private Duration retryDelay = Duration.ofSeconds(30);
        private Duration processingTimeout = Duration.ofMinutes(5);
        private Duration pollInterval = Duration.ofSeconds(30);
        private int pollBatchSize = 20;

        public String repository() {
            return repository;
        }

        public String getRepository() {
            return repository;
        }

        public void setRepository(String repository) {
            this.repository = repository;
        }

        public int maxAttempts() {
            return maxAttempts;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("feishu inbox max-attempts must be positive");
            }
            this.maxAttempts = maxAttempts;
        }

        public Duration retryDelay() {
            return retryDelay;
        }

        public Duration getRetryDelay() {
            return retryDelay;
        }

        public void setRetryDelay(Duration retryDelay) {
            this.retryDelay = positive(retryDelay, "retry-delay");
        }

        public Duration processingTimeout() {
            return processingTimeout;
        }

        public Duration getProcessingTimeout() {
            return processingTimeout;
        }

        public void setProcessingTimeout(Duration processingTimeout) {
            this.processingTimeout = positive(processingTimeout, "processing-timeout");
        }

        public Duration pollInterval() {
            return pollInterval;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = positive(pollInterval, "poll-interval");
        }

        public int pollBatchSize() {
            return pollBatchSize;
        }

        public int getPollBatchSize() {
            return pollBatchSize;
        }

        public void setPollBatchSize(int pollBatchSize) {
            if (pollBatchSize < 1) {
                throw new IllegalArgumentException("feishu inbox poll-batch-size must be positive");
            }
            this.pollBatchSize = pollBatchSize;
        }

        private Duration positive(Duration value, String property) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("feishu inbox " + property + " must be positive");
            }
            return value;
        }
    }

    public static class Outbox {

        private String repository = "memory";
        private int maxAttempts = 5;
        private Duration retryDelay = Duration.ofSeconds(30);
        private Duration processingTimeout = Duration.ofMinutes(5);
        private Duration pollInterval = Duration.ofSeconds(5);
        private int pollBatchSize = 20;

        public String repository() {
            return repository;
        }

        public String getRepository() {
            return repository;
        }

        public void setRepository(String repository) {
            this.repository = repository;
        }

        public int maxAttempts() {
            return maxAttempts;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("feishu outbox max-attempts must be positive");
            }
            this.maxAttempts = maxAttempts;
        }

        public Duration retryDelay() {
            return retryDelay;
        }

        public Duration getRetryDelay() {
            return retryDelay;
        }

        public void setRetryDelay(Duration retryDelay) {
            this.retryDelay = positive(retryDelay, "retry-delay");
        }

        public Duration processingTimeout() {
            return processingTimeout;
        }

        public Duration getProcessingTimeout() {
            return processingTimeout;
        }

        public void setProcessingTimeout(Duration processingTimeout) {
            this.processingTimeout = positive(processingTimeout, "processing-timeout");
        }

        public Duration pollInterval() {
            return pollInterval;
        }

        public Duration getPollInterval() {
            return pollInterval;
        }

        public void setPollInterval(Duration pollInterval) {
            this.pollInterval = positive(pollInterval, "poll-interval");
        }

        public int pollBatchSize() {
            return pollBatchSize;
        }

        public int getPollBatchSize() {
            return pollBatchSize;
        }

        public void setPollBatchSize(int pollBatchSize) {
            if (pollBatchSize < 1) {
                throw new IllegalArgumentException("feishu outbox poll-batch-size must be positive");
            }
            this.pollBatchSize = pollBatchSize;
        }

        private Duration positive(Duration value, String property) {
            if (value == null || value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("feishu outbox " + property + " must be positive");
            }
            return value;
        }
    }
}
