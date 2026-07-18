package com.hkdzagent.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record SearchRequest(
        @JsonProperty(required = true, value = "query")
        @JsonPropertyDescription("Search query for public web information.")
        String query
) {
}
