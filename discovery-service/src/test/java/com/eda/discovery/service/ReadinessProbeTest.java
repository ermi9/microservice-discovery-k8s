package com.eda.discovery.service;

import com.eda.discovery.controller.DiscoveryController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Readiness reflects dependencies; liveness does not.
 *
 * <p>The property: {@code /ready} reports not-ready when Redis is unreachable, while
 * {@code /health} stays a dependency-free liveness signal.
 *
 * <p>Both halves are asserted because either one alone is a failure mode. A readiness
 * probe that ignores Redis leaves a pod that cannot read the registry taking traffic; a
 * liveness probe that checks Redis lets a dependency blip make the kubelet kill and
 * restart every discovery pod at once.
 *
 * <p>Scope: the probe's own logic. That the kubelet actually gates traffic on the result
 * is cluster-only and not exercised here.
 */
@SuppressWarnings("unchecked")
class ReadinessProbeTest {

    @SuppressWarnings("unchecked")
    private static DiscoveryController controllerWithRedis(boolean redisUp) {
        RedisTemplate<String, Object> template = mock(RedisTemplate.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisConnection connection = mock(RedisConnection.class);

        when(template.getConnectionFactory()).thenReturn(factory);
        if (redisUp) {
            when(factory.getConnection()).thenReturn(connection);
            when(connection.ping()).thenReturn("PONG");
        } else {
            when(factory.getConnection())
                    .thenThrow(new RedisConnectionFailureException("Unable to connect to Redis"));
        }

        DiscoveryController controller = new DiscoveryController();
        ReflectionTestUtils.setField(controller, "redisTemplate", template);
        return controller;
    }

    @Test
    @DisplayName("/ready is 200 READY when Redis answers")
    void readyWhenRedisUp() {
        ResponseEntity<?> response = controllerWithRedis(true).ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("status", "READY");
    }

    @Test
    @DisplayName("/ready is 503 NOT_READY when Redis is unreachable")
    void notReadyWhenRedisDown() {
        ResponseEntity<?> response = controllerWithRedis(false).ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("status", "NOT_READY").containsEntry("redis", "DOWN");
        assertThat(body.get("reason")).asString().contains("Unable to connect to Redis");
    }

    @Test
    @DisplayName("/health stays 200 even when Redis is down (liveness must not cascade)")
    void livenessIsIndependentOfRedis() {
        // If this ever starts failing on a Redis outage, the kubelet will restart every
        // discovery pod at once — the exact cascade the /health,/ready split prevents.
        assertThat(controllerWithRedis(false).health().getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
