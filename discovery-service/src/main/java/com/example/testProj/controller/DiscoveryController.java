package com.example.testProj.controller;

import com.example.testProj.kubernetes.KubernetesDiscoveryService;
import com.example.testProj.model.Service;
import com.example.testProj.service.ServiceRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/")
public class DiscoveryController {
    
        @Autowired
    private ServiceRegistry serviceRegistry;
    @Autowired
    private KubernetesDiscoveryService kubernetesDiscoveryService;
    
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String url = request.get("url");
        String openapiUrl = request.get("openapiUrl");
        
        if (name == null || url == null || openapiUrl == null) {
            return ResponseEntity.badRequest().body("Missing required fields: name, url, openapiUrl");
        }
        
        Service service = new Service(name, url, openapiUrl);
        serviceRegistry.register(service);
        
        return ResponseEntity.ok(Map.of("message", "Service registered successfully", "service", name));
    }
    
    @GetMapping("/services")
    public ResponseEntity<List<Service>> getAllServices() {

        List<Service> services = serviceRegistry.getAllServices();
        
        //since there is an associated pod with each service, we can include the pod infor on the resopnse
        for (Service service:services){
            Map<String,Object> podInfo=findPodForService(service.getName());
            service.setPod(podInfo);
        }

        return ResponseEntity.ok(services);
    }
    
    @GetMapping("/services/{name}")
    public ResponseEntity<?> getServiceByName(@PathVariable String name) {
        Service service=serviceRegistry.getServiceByName(name);
        
        if (service == null) {
            return ResponseEntity.notFound().build();
        }

        //adding pod info into the response
        Map<String,Object>podInfo=findPodForService(name);
        service.setPod(podInfo);

        return ResponseEntity.ok(service);
    }

    private Map<String, Object> findPodForService(String serviceName){

        try{
            String labelSelector = "app=" + serviceName;
            List <Map<String,Object>>pods=kubernetesDiscoveryService.getPodsByLabel("default",labelSelector);

            for(Map<String,Object> pod:pods){
                if("Running".equals(pod.get("status"))){
                    return pod;
                }
            }
            return null;
        }
        catch(Exception e){
            System.err.println("Error finding pod for service " + serviceName + ": " + e.getMessage());
        return null;
        }
        
        
    }
    
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }
}
