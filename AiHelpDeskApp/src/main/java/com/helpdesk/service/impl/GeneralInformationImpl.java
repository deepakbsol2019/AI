package com.helpdesk.service.impl;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import com.helpdesk.service.GeneralInformation;

@Service
public class GeneralInformationImpl implements GeneralInformation {

	@Override
	public LocalDateTime getCurrentDateTime() {
		return LocalDateTime.now();
	}

}
