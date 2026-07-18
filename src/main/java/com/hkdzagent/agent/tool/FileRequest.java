package com.hkdzagent.agent.tool;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

public record FileRequest(
        @JsonProperty(required = true, value = "filePath")
        @JsonPropertyDescription("Local file path or file name to write.")
        String filePath,

        @JsonProperty(required = true, value = "content")
        @JsonPropertyDescription("Text content to write to the file.")
        String content
) {
}
