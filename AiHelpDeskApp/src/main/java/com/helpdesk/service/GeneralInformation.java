package com.helpdesk.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
@Service
public interface GeneralInformation {
	
	public LocalDateTime getCurrentDateTime();

}
