package com.example.testProj.repository;

import com.example.testProj.model.Service;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceRepository extends JpaRepository<Service, UUID> {
    Optional<Service> findByName(String name);
    
    Optional<Service> findById(UUID id);
}
