package com.tool.service.impl;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.TimeZone;

import org.springframework.stereotype.Service;

import com.tool.service.ToolService;
@Service
public class ToolServiceImpl implements ToolService {

	@Override
	public Map<ZoneId, Instant> getUTCTime() {
		ZoneId zoneId = ZoneId.systemDefault();
		Instant now =Instant.now();
		System.out.println("ZoneId ::"+zoneId+" UTC ::"+now);
		return Map.of(zoneId,now);
	}

	@Override
	public String setMyAlarm(LocalDateTime localDateTime) {
		System.out.println("Alarm set completed at ::"+localDateTime);
		return localDateTime.toString();
	}

}
