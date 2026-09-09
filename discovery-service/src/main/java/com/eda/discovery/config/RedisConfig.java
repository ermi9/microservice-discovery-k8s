package com.eda.discovery.config;

import io.lettuce.core.ReadFrom;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.convert.RedisCustomConversions;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    public RedisCustomConversions redisCustomConversions() {
        return new RedisCustomConversions(List.of(
            new LocalDateTimeToByteArrayConverter(),
            new ByteArrayToLocalDateTimeConverter()
        ));
    }

    @WritingConverter
    static class LocalDateTimeToByteArrayConverter implements Converter<LocalDateTime, byte[]> {
        @Override
        public byte[] convert(LocalDateTime source) {
            return source.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    @ReadingConverter
    static class ByteArrayToLocalDateTimeConverter implements Converter<byte[], LocalDateTime> {
        @Override
        public LocalDateTime convert(byte[] source) {
            return LocalDateTime.parse(new String(source, StandardCharsets.UTF_8));
        }
    }

    // Bound to the spring.data.redis.* properties, with the deployed hostnames as
    // defaults, so the topology can be repointed without a rebuild.
    @Value("${spring.data.redis.host:redis-master}")
    private String masterHost;

    @Value("${spring.data.redis.port:6379}")
    private int masterPort;

    @Value("${spring.data.redis.replica-host:redis-replica}")
    private String replicaHost;

    @Value("${spring.data.redis.replica-port:6379}")
    private int replicaPort;

    // --- MASTER/REPLICA TOPOLOGY (key-value operations) ---
    @Primary
    @Bean
    public LettuceConnectionFactory redisConnectionFactory() {
        // Route writes to the master, add the replica nodes
        RedisStaticMasterReplicaConfiguration topology =
                new RedisStaticMasterReplicaConfiguration(masterHost, masterPort);
        if (!replicaHost.isBlank() && !replicaHost.equals(masterHost)) {
            topology.addNode(replicaHost, replicaPort);
        }

        // Force reads to be distributed across the replicas
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .readFrom(ReadFrom.REPLICA_PREFERRED)
                .build();

        return new LettuceConnectionFactory(topology, clientConfig);
    }

    // Separate direct-master connection for pub/sub — pub/sub must go to master, not replicas
    @Bean("pubSubConnectionFactory")
    public LettuceConnectionFactory pubSubConnectionFactory() {
        return new LettuceConnectionFactory(masterHost, masterPort);
    }

    // StringRedisTemplate used for pub/sub publishing (convertAndSend)
    @Bean
    public StringRedisTemplate stringRedisTemplate(
            @Qualifier("pubSubConnectionFactory") LettuceConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    // Container that manages pub/sub subscriptions for FollowerRelayService
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            @Qualifier("pubSubConnectionFactory") LettuceConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        return container;
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        Jackson2JsonRedisSerializer<Object> jackson2JsonRedisSerializer =
            new Jackson2JsonRedisSerializer<>(Object.class);
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        jackson2JsonRedisSerializer.setObjectMapper(objectMapper);

        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(jackson2JsonRedisSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(jackson2JsonRedisSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
