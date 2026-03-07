package com.bko.bpmn_engine.ai;

import com.bko.bpmn_engine.api.dto.ChatMessageDto;

import java.util.List;

public record AiProviderRequest(
        String systemInstruction,
        String model,
        List<ChatMessageDto> messages
) {
}
