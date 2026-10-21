package com.helpdesk.tools;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.ai.tool.annotation.Tool;

public interface GeneralTool {


	@Tool(
	        description = "Get the current date and time from the server",
	        returnDirect = false
	    )
	    LocalDateTime getCurrentDateTime();

	    @Tool(
	        description = "Get today's current date from the server",
	        returnDirect = false
	    )
	    LocalDate getTodayDate();
}
