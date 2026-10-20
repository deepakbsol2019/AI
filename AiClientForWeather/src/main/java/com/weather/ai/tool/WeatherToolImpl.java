package com.weather.ai.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.weather.ai.external.ExternalWeatherApiCall;

@Service
public class WeatherToolImpl implements WeatherTool {

	@Value("${app.weathe.api-key}")
	private String apiKey;
	
	private ExternalWeatherApiCall externalWeatherApiCall;
	public WeatherToolImpl(ExternalWeatherApiCall externalWeatherApiCall) {
		this.externalWeatherApiCall=externalWeatherApiCall;
	}
	@Override
	public String getWeatherInformationByCityName(String cityName) {
		System.out.println("3 ::");
		 String cityWeather = externalWeatherApiCall.getCityWeather(apiKey, cityName);
		 System.out.println("city weather ::"+cityName +" :: "+cityWeather);
		 return cityWeather;
	}

}
