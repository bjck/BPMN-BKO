package com.bko.bpmn_engine.engine;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component("counterServiceTaskLogic")
public class CounterServiceTaskLogic implements ServiceTaskLogic {

    @Override
    public String displayName() {
        return "Counter Logic";
    }

    @Override
    public String description() {
        return "Increments the process variable 'counter' by the FEEL input 'step' or 1 by default.";
    }

    @Override
    public Object execute(ServiceTaskExecutionContext context) {
        int current = ((Number) context.variables().getOrDefault("counter", 0)).intValue();
        int step = ((Number) context.inputs().getOrDefault("step", 1)).intValue();
        return Map.of("counter", current + step);
    }
}
