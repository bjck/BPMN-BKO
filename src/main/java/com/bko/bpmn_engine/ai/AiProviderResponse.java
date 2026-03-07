package com.bko.bpmn_engine.ai;

import java.util.Map;

public record AiProviderResponse(
        String model,
        String content,
        Map<String, Object> usage
) {
}
