package com.example.testProj.repository;

import com.example.testProj.model.PodSnapshot;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface PodSnapshotRepository extends CrudRepository<PodSnapshot, UUID> {
    List<PodSnapshot> findByServiceId(UUID serviceId);
}
