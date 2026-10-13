package com.gemini.chatservice;

import java.util.List;

import com.gemini.model.ProgrammingLagModel;

public interface ProgrammingLangService {

	ProgrammingLagModel getDtls(String userQuestion);

	List<ProgrammingLagModel> getDtlsList(String userQuestion);

}
