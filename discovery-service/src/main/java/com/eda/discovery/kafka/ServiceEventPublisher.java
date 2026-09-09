package com.eda.discovery.kafka;

import com.eda.discovery.model.ServiceEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class ServiceEventPublisher {

    @Value("${service-events.topic.name:service-events}")
    private String topic;

    @Autowired
    private KafkaTemplate<String, ServiceEvent> kafkaTemplate;

    public void publish(ServiceEvent event) {
        // Service name as the key — guarantees all events for the same service
        // land on the same partition and are consumed in order. A DEREGISTERED must
        // never overtake the REGISTERED it followed, and it is also what makes log
        // compaction meaningful: the last event per service survives forever.
        kafkaTemplate.send(topic, event.getServiceName(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // Surfaced rather than swallowed: a failed publish means every
                        // consumer's view of this service is now silently out of date.
                        System.err.println("[Kafka] FAILED to publish " + event.getType()
                                + " for " + event.getServiceName() + ": " + ex.getMessage());
                    } else {
                        System.out.println("[Kafka] Published " + event.getType()
                                + " for " + event.getServiceName());
                    }
                });
    }
}
