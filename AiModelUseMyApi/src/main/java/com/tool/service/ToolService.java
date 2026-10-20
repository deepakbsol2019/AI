package com.tool.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.TimeZone;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

public interface ToolService {
	@Tool(description = "Use this function to get the Map of user ZoneId as key and current utc date time as value")
	public Map<ZoneId, Instant> getUTCTime();
	
	@Tool(description = "Use this function to set the Alarm. This function required one Input parameter LocalDateTime type")
	public String setMyAlarm(@ToolParam(description = "localDateTime is the given date time to set the alarm. this is of type LocalDateTime.use this parameter in user time zone and set the alarm") LocalDateTime localDateTime);
}
