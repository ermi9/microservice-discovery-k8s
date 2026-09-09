package com.eda.discovery.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PartitionManager {

    @Value("${partition.count:3}")
    private int partitionCount;

    /**
     * Maps a service name onto a partition.
     *
     * <p>{@code Math.floorMod} rather than {@code Math.abs(x % n)}: {@code Math.abs} of
     * {@link Integer#MIN_VALUE} is still {@code Integer.MIN_VALUE}, so a name hashing to
     * that value produced a negative partition index and an ArrayIndexOutOfBounds
     * downstream. {@code floorMod} is always in {@code [0, partitionCount)}.
     */
    public int getPartitionForService(String serviceName) {
        return Math.floorMod(serviceName.hashCode(), partitionCount);
    }

    public int getPartitionCount() {
        return partitionCount;
    }
}
