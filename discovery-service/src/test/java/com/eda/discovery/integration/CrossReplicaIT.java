package com.eda.discovery.integration;

import com.eda.discovery.config.HealthCheckConfig;
import com.eda.discovery.kafka.ServiceEventPublisher;
import com.eda.discovery.model.Service;
import com.eda.discovery.model.ServiceStatus;
import com.eda.discovery.repository.ServiceRepository;
import com.eda.discovery.service.ServiceRegistry;
import com.eda.discovery.service.TopicNamingStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Registry completeness across replicas.
 *
 * <p>The property: a service registered against any replica is resolvable from any other,
 * and a status change applied on one is visible on the rest. This is what lets the
 * Kubernetes Watch — which runs on whichever replica leads the partition, not on the one
 * that handled registration — apply and publish a transition at all.
 *
 * <p><b>What a "replica" is here:</b> two {@link ServiceRegistry} instances over one
 * shared Redis. That is faithful — replicas are separate registry instances sharing
 * Redis, and leader election is Redis-based rather than pod-identity-based. What it does
 * not cover is a real partition-leader move, which is cluster-only.
 */
@SpringBootTest
class CrossReplicaIT {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.replica-host", REDIS::getHost);
        registry.add("spring.data.redis.replica-port", () -> REDIS.getMappedPort(6379));
        // No broker in this test: publishing is asserted elsewhere (WireContractIT).
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    /**
     * Publishing is out of scope here and asserted in {@code WireContractIT}. It is
     * mocked because {@code register()} propagates a synchronous send failure when no
     * broker is reachable.
     */
    @MockitoBean
    private ServiceEventPublisher eventPublisher;

    /** The registry bean from the application context — "replica A". */
    @Autowired
    private ServiceRegistry replicaA;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /** A second, independently constructed registry over the same Redis — "replica B". */
    private ServiceRegistry replicaB;

    @BeforeEach
    void setUp() {
        serviceRepository.deleteAll();

        replicaB = new ServiceRegistry();
        ReflectionTestUtils.setField(replicaB, "serviceRepository", serviceRepository);
        ReflectionTestUtils.setField(replicaB, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(replicaB, "eventPublisher", Mockito.mock(ServiceEventPublisher.class));
        ReflectionTestUtils.setField(replicaB, "healthCheckConfig", Mockito.mock(HealthCheckConfig.class));

        TopicNamingStrategy naming = new TopicNamingStrategy();
        ReflectionTestUtils.setField(naming, "inputSuffix", ".in");
        ReflectionTestUtils.setField(naming, "compensationSuffix", ".compensate");
        ReflectionTestUtils.setField(replicaB, "topicNaming", naming);
    }

    @AfterEach
    void tearDown() {
        serviceRepository.deleteAll();
    }

    @Test
    @DisplayName("a service registered on replica A is resolvable on replica B")
    void registrationIsVisibleAcrossReplicas() {
        replicaA.register(new Service("order-service", "http://order:8080", "http://order:8080/docs"));

        Service seenByB = replicaB.getServiceByName("order-service");

        assertThat(seenByB).as("replica B must see a registration it never handled").isNotNull();
        assertThat(seenByB.getUrl()).isEqualTo("http://order:8080");
        assertThat(seenByB.getInputTopic()).isEqualTo("order-service.in");
        assertThat(replicaB.getAllServices()).extracting(Service::getName).contains("order-service");
    }

    @Test
    @DisplayName("a status change on replica B is visible on replica A")
    void statusChangesPropagateAcrossReplicas() {
        replicaA.register(new Service("payment-service", "http://payment:8080", "http://payment:8080/docs"));

        // The Watch runs on whichever replica leads the partition -- usually not the one
        // that handled registration.
        replicaB.updateServiceStatus("payment-service", ServiceStatus.HEALTHY, 3);

        Service seenByA = replicaA.getServiceByName("payment-service");
        assertThat(seenByA.getStatus()).isEqualTo(ServiceStatus.HEALTHY);
        assertThat(seenByA.getStatusGeneration()).isEqualTo(3);
    }

    @Test
    @DisplayName("the generation fence is shared state, not per-replica memory")
    void fenceIsSharedAcrossReplicas() {
        replicaA.register(new Service("svc", "http://svc:8080", "http://svc:8080/docs"));
        replicaA.updateServiceStatus("svc", ServiceStatus.UNAVAILABLE, 5);

        // A deposed leader on another replica replays an older generation. If the fence
        // lived in per-replica memory instead of Redis, replica B would know nothing of
        // generation 5 and would happily apply this.
        replicaB.updateServiceStatus("svc", ServiceStatus.HEALTHY, 2);

        assertThat(replicaA.getServiceByName("svc").getStatus())
                .as("stale generation from another replica must still be fenced")
                .isEqualTo(ServiceStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("deregistration on one replica removes the service everywhere")
    void deregistrationIsVisibleAcrossReplicas() {
        replicaA.register(new Service("temp-service", "http://temp:8080", "http://temp:8080/docs"));
        assertThat(replicaB.getServiceByName("temp-service")).isNotNull();

        replicaB.deregister("temp-service");

        assertThat(replicaA.getServiceByName("temp-service")).isNull();
        assertThat(replicaA.getAllServices()).extracting(Service::getName).doesNotContain("temp-service");
    }

    @Test
    @DisplayName("registration is idempotent regardless of which replica handles it")
    void registrationIsIdempotentAcrossReplicas() {
        replicaA.register(new Service("svc", "http://svc:8080", "http://svc:8080/docs"));
        replicaB.register(new Service("svc", "http://svc:9090", "http://svc:9090/docs"));

        List<Service> all = replicaA.getAllServices();
        assertThat(all).extracting(Service::getName).filteredOn("svc"::equals).hasSize(1);
        assertThat(replicaA.getServiceByName("svc").getUrl())
                .as("last write wins, no duplicate entry")
                .isEqualTo("http://svc:9090");
    }
}
