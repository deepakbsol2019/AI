package com.weather.ai.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public interface WeatherTool {

	@Tool(description = "Use This Service to fetch weather information of the given input city name")
	public String getWeatherInformationByCityName(@ToolParam(description = "City Name will be the inpu of type String. By Using this input service will return the weather information of the city") String cityName);
	
}
