package com.classroom.cbsc.service;

import com.classroom.response.ResponseModel;

public interface TenthClassService {

	ResponseModel getAiResponse(Long id, String subject, String question);

}
