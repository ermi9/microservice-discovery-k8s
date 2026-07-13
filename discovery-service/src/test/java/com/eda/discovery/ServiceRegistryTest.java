package com.eda.discovery;

import com.eda.discovery.config.HealthCheckConfig;
import com.eda.discovery.kafka.ServiceEventPublisher;
import com.eda.discovery.model.Service;
import com.eda.discovery.model.ServiceEvent;
import com.eda.discovery.repository.ServiceRepository;
import com.eda.discovery.service.ServiceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Behavioural tests for the Redis-backed {@link ServiceRegistry}: registration,
 * status updates, and deregistration all persist through the shared repository and
 * emit the correct routing events.
 */
@ExtendWith(MockitoExtension.class)
class ServiceRegistryTest {

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private HealthCheckConfig healthCheckConfig;

    @Mock
    private ServiceEventPublisher eventPublisher;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @InjectMocks
    private ServiceRegistry serviceRegistry;

    @Test
    void register_new_service_persists_and_publishes_registered_event() {
        Service incoming = new Service("service-a", "http://service-a:8080",
                "http://service-a:8080/v3/api-docs");
        when(serviceRepository.findById("service-a")).thenReturn(Optional.empty());

        serviceRegistry.register(incoming);

        // A brand-new service is persisted with the initial "unknown" status.
        ArgumentCaptor<Service> saved = ArgumentCaptor.forClass(Service.class);
        verify(serviceRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("service-a");
        assertThat(saved.getValue().getStatus()).isEqualTo("unknown");

        ArgumentCaptor<ServiceEvent> event = ArgumentCaptor.forClass(ServiceEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().getType()).isEqualTo(ServiceEvent.Type.SERVICE_REGISTERED);
        assertThat(event.getValue().getOpenapiUrl()).isEqualTo("http://service-a:8080/v3/api-docs");
    }

    @Test
    void updateServiceStatus_persists_and_publishes_status_changed_with_generation() {
        Service existing = new Service("service-b", "http://service-b:8080",
                "http://service-b:8080/v3/api-docs");
        when(serviceRepository.findById("service-b")).thenReturn(Optional.of(existing));

        serviceRegistry.updateServiceStatus("service-b", "healthy", 7L);

        verify(serviceRepository).save(existing);
        assertThat(existing.getStatus()).isEqualTo("healthy");

        ArgumentCaptor<ServiceEvent> event = ArgumentCaptor.forClass(ServiceEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().getType()).isEqualTo(ServiceEvent.Type.STATUS_CHANGED);
        assertThat(event.getValue().getStatus()).isEqualTo("healthy");
        assertThat(event.getValue().getGeneration()).isEqualTo(7L);
    }

    @Test
    void updateServiceStatus_is_a_noop_when_service_is_absent() {
        when(serviceRepository.findById("ghost")).thenReturn(Optional.empty());

        serviceRegistry.updateServiceStatus("ghost", "healthy", 1L);

        verify(serviceRepository, never()).save(any(Service.class));
        verify(eventPublisher, never()).publish(any(ServiceEvent.class));
    }

    @Test
    void deregister_marks_service_unavailable_and_publishes_deregistered_event() {
        Service existing = new Service("service-c", "http://service-c:8080",
                "http://service-c:8080/v3/api-docs");
        existing.setStatus("healthy");
        when(serviceRepository.findById("service-c")).thenReturn(Optional.of(existing));

        serviceRegistry.deregister("service-c");

        verify(serviceRepository).save(existing);
        assertThat(existing.getStatus()).isEqualTo("unavailable");

        ArgumentCaptor<ServiceEvent> event = ArgumentCaptor.forClass(ServiceEvent.class);
        verify(eventPublisher).publish(event.capture());
        assertThat(event.getValue().getType()).isEqualTo(ServiceEvent.Type.SERVICE_DEREGISTERED);
        assertThat(event.getValue().getStatus()).isEqualTo("unavailable");
    }

    @Test
    void getServiceByName_returns_null_when_absent() {
        when(serviceRepository.findById("nope")).thenReturn(Optional.empty());

        assertThat(serviceRegistry.getServiceByName("nope")).isNull();
    }
}
