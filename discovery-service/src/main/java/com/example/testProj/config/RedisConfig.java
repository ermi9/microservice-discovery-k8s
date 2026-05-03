package com.example.testProj.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.convert.RedisCustomConversions;
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

    @Bean
    @ConditionalOnMissingBean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
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
