package com.weather.ai.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.weather.ai.service.WeatherAgentService;

@RestController
@RequestMapping("/api")
public class WeatherAgentServiceController {

	private final WeatherAgentService weatherAgentService;
	public WeatherAgentServiceController(WeatherAgentService weatherAgentService) {
		this.weatherAgentService=weatherAgentService;
	}
	@GetMapping("/test")
	public String getCityWeather(@RequestParam String question) {
		System.out.println("1 ::");
		return weatherAgentService.getWeatherByCity(question);
	}
	
}
