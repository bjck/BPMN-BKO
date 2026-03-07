package com.bko.bpmn_engine.ai;

public interface AiProviderClient {

    AiProviderResponse generate(AiProviderRequest request) throws Exception;
}
