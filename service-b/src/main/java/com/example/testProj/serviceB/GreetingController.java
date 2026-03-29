package com.example.testProj.serviceB;
//import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
@RestController
public class GreetingController {

    //@Value("${discovery.service.url:http://localhost:8080}")
    //private String discoveryServiceUrl;

    @GetMapping("/greet")
    public Map<String,String> greet(@RequestParam String name){
        return Map.of("message", "Hello, " + name + "!"+ "from service B",
                "timestamp", LocalDateTime.now().toString()
        );
    }
    @GetMapping("/health")
    public String health(){
        return "OK";
    }  
}