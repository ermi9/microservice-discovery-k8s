package com.eda.discovery.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PartitionManager {

    @Value("${partition.count:3}")
    private int partitionCount;

    public int getPartitionForService(String serviceName) {
        return Math.abs(serviceName.hashCode() % partitionCount);
    }

    public int getPartitionCount() {
        return partitionCount;
    }
}
