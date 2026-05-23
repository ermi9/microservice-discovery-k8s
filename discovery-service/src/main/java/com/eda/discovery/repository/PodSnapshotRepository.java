package com.eda.discovery.repository;

import com.eda.discovery.model.PodSnapshot;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface PodSnapshotRepository extends CrudRepository<PodSnapshot, UUID> {
    List<PodSnapshot> findByServiceName(String serviceName);
}
