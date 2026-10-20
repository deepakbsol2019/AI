package com.weather.ai.external;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(
		name = "weatherapi",
		url = "https://api.weatherapi.com/v1"
		)
public interface ExternalWeatherApiCall {

	@GetMapping("/current.json")
	public String getCityWeather(@RequestParam("key") String key, @RequestParam("q") String q);
}
