package com.hah.here;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HereApplication {

	public static void main(String[] args) {
		SpringApplication.run(HereApplication.class, args);
	}

}
