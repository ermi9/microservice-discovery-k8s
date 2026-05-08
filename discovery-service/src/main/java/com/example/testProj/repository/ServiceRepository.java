package com.example.testProj.repository;

import com.example.testProj.model.Service;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

//changed
//
@Repository
public interface ServiceRepository extends CrudRepository<Service, String> {

}
