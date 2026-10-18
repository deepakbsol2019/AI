package com.store.service;

import java.nio.file.Path;
import java.util.List;

public interface ReadCSVService {
	
	public List<String> readDataFromCsv();
	
	public void saveDataInVector(List<String> data);

}
