package com.weather.ai.service.impl;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.weather.ai.service.WeatherAgentService;
import com.weather.ai.tool.WeatherTool;
import com.weather.ai.tool.WeatherToolImpl;
@Service
public class WeatherAgentServiceImpl implements WeatherAgentService{

	private final WeatherTool weatherTool;
	private final ChatClient chatClient;
	public WeatherAgentServiceImpl(WeatherTool weatherTool,ChatClient chatClient) {
		this.weatherTool=weatherTool;
		this.chatClient=chatClient;
	}
	@Override
	public String getWeatherByCity(String question) {
		System.out.println("2 ::");
		return chatClient.prompt()
		.user(question)
		.tools(weatherTool)
		.call()
		.content();
	}

}
