package com.helpdesk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiHelpDeskAppApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiHelpDeskAppApplication.class, args);
	}

}
