package com.eda.discovery.kafka;

import com.eda.discovery.model.ServiceEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class ServiceEventPublisher {

    static final String TOPIC = "service-events";

    @Autowired
    private KafkaTemplate<String, ServiceEvent> kafkaTemplate;

    public void publish(ServiceEvent event) {
        // Service name as the key — guarantees all events for the same service
        // land on the same partition and are consumed in order.
        kafkaTemplate.send(TOPIC, event.getServiceName(), event);
        System.out.println("[Kafka] Published " + event.getType() + " for " + event.getServiceName());
    }
}
