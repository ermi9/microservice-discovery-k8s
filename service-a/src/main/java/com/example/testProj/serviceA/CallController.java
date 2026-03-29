package com.example.testProj.serviceA;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import java.util.Map;
@RestController
public class CallController {
    private final RestTemplate restTemplate=new RestTemplate();

    //i can now inject the url instead of hardcoding it
    //from application.properties or environment variable
    @Value("${service.b.url:http://localhost:8081}")
    private String serviceBUrl;
    @GetMapping("/call")
    public Map<String, Object> call(@RequestParam String name){
        String url = serviceBUrl + "/greet?name=" + name;
        
        Map responseFromB = restTemplate.getForObject(url, Map.class);        
        
        return Map.of("fromA", "processed by service A",
            "FromB", responseFromB
    );
}
    @GetMapping("/health")
    public String health(){
        return "OK";
    }
}
