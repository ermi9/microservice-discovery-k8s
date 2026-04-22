package com.example.testProj.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;

@Service
public class CacheService {
    
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;
    
    /**
     * Get a value from cache
     */
    public Object get(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            System.err.println("Cache GET failed for key: " + key + " - " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Set a value in cache with TTL (Time To Live)
     */
    public void set(String key, Object value, long ttlSeconds) {
        try {
            redisTemplate.opsForValue().set(key, value, ttlSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("Cache SET failed for key: " + key + " - " + e.getMessage());
        }
    }
    
    /**
     * Delete a key from cache
     */
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            System.err.println("Cache DELETE failed for key: " + key + " - " + e.getMessage());
        }
    }
    
    /**
     * Check if a key exists in cache
     */
    public boolean exists(String key) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            System.err.println("Cache EXISTS check failed for key: " + key + " - " + e.getMessage());
            return false;
        }
    }
}
