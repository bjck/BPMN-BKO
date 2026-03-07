package com.bko.bpmn_engine.engine;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class ServiceTaskLogicRegistry {

    private final Map<String, ServiceTaskLogic> serviceTaskLogics;

    public ServiceTaskLogicRegistry(Map<String, ServiceTaskLogic> serviceTaskLogics) {
        this.serviceTaskLogics = serviceTaskLogics;
    }

    public ServiceTaskLogic getByBeanName(String beanName) {
        return serviceTaskLogics.get(beanName);
    }

    public List<ServiceTaskLogicDescriptor> listDescriptors() {
        return serviceTaskLogics.entrySet().stream()
                .map(entry -> new ServiceTaskLogicDescriptor(
                        entry.getKey(),
                        entry.getValue().displayName(),
                        entry.getValue().description()
                ))
                .sorted(Comparator.comparing(ServiceTaskLogicDescriptor::displayName))
                .toList();
    }

    public record ServiceTaskLogicDescriptor(String beanName, String displayName, String description) {
    }
}
