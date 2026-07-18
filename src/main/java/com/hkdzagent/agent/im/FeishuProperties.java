package com.hkdzagent.agent.im;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "feishu")
public class FeishuProperties {

    private String appId;
    private String appSecret;
    private String verificationToken = "";
    private String encryptKey = "";
    private Async async = new Async();

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
}
