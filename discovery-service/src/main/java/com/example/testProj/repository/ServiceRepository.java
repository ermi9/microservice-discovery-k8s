package com.example.testProj.repository;

import com.example.testProj.model.Service;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ServiceRepository extends CrudRepository<Service, UUID> {
    Optional<Service> findByName(String name);
    
    Optional<Service> findById(UUID id);
}
