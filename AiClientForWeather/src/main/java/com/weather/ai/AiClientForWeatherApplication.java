package com.weather.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class AiClientForWeatherApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiClientForWeatherApplication.class, args);
	}

}
