package com.example.testProj;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TestProjApplication {

	public static void main(String[] args) {
		SpringApplication.run(TestProjApplication.class, args);
	}

}
