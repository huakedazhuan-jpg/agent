package com.hkdzagent.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record WebRequest(
        @JsonProperty(required = true, value = "url")
        @JsonPropertyDescription("HTTP or HTTPS URL to request.")
        String url
) {
}
