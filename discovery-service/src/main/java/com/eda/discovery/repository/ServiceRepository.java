package com.eda.discovery.repository;

import com.eda.discovery.model.Service;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

//
@Repository
public interface ServiceRepository extends CrudRepository<Service, String> {

}
