package com.helpdesk.tools.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.helpdesk.service.GeneralInformation;
import com.helpdesk.tools.GeneralTool;

@Service
public class GeneralToolImpl implements GeneralTool{

	private final GeneralInformation generalInformation;
	public GeneralToolImpl(GeneralInformation generalInformation) {
		this.generalInformation=generalInformation;
	}
	@Override
	public LocalDateTime getCurrentDateTime() {
		return generalInformation.getCurrentDateTime();
	}
	@Override
	public LocalDate getTodayDate() {
		return LocalDate.now();
	}
}
