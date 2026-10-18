package com.store.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public class ReadCSV {


    public static List<String> readStatements(Path filePath) throws IOException {

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()                  // First row se column names lo
                .setSkipHeaderRecord(true)    // Header ko data mein mat lo
                .get();

        List<String> statements = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(
                    filePath, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {

            for (CSVRecord record : parser) {
                statements.add(record.get(0)); // First column ka statement
            }
        }

        return statements;
    }
	
}
