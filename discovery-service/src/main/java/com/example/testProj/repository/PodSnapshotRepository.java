package com.example.testProj.repository;

import com.example.testProj.model.PodSnapshot;
import com.example.testProj.model.Service;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface PodSnapshotRepository extends JpaRepository<PodSnapshot, UUID> {
    List<PodSnapshot> findByService(Service service);
    
    List<PodSnapshot> findByServiceId(UUID serviceId);
}
