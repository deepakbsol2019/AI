package com.classroom.util;

import java.util.Locale;

import org.springframework.stereotype.Component;

@Component
public class DynamicPromptBuilder {
	
	public String build(String studentClass, String subject) {

	    return String.join("\n\n",
	        SystemTempletConstant.COMMON,
	        getClassRules(studentClass),
	        getSubjectRules(subject),
	        SystemTempletConstant.DIAGRAM,
	        SystemTempletConstant.RESPONSE_FORMAT
	    );
	}

	private String getClassRules(String studentClass) {

	    if (studentClass == null || studentClass.isBlank()) {
	        throw new IllegalArgumentException(
	            "Student class cannot be empty"
	        );
	    }

	    String value = studentClass.trim()
	            .toLowerCase(java.util.Locale.ROOT)
	            .replaceAll("\\s+", " ")
	            .replaceFirst("^class\\s+", "");

	    return switch (value) {

	        case "1", "1st", "one", "first",
	             "2", "2nd", "two", "second",
	             "3", "3rd", "three", "third" ->
	                SystemTempletConstant.PRIMARY_RULES;

	        case "4", "4th", "four", "fourth",
	             "5", "5th", "five", "fifth" ->
	                SystemTempletConstant.ELEMENTARY_RULES;

	        case "6", "6th", "six", "sixth",
	             "7", "7th", "seven", "seventh",
	             "8", "8th", "eight", "eighth" ->
	                SystemTempletConstant.MIDDLE_RULES;

	        case "9", "9th", "nine", "ninth",
	             "10", "10th", "ten", "tenth" ->
	                SystemTempletConstant.SECONDARY_RULES;

	        case "11", "11th", "eleven", "eleventh",
	             "12", "12th", "twelve", "twelfth" ->
	                SystemTempletConstant.SENIOR_RULES;

	        default -> SystemTempletConstant.GENERAL_LEVEL_RULES;
	    };
	}

    private String getSubjectRules(String subject) {

        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException(
                "Subject cannot be empty");
        }

        return switch (
            subject.trim().toLowerCase(Locale.ROOT)
        ) {
            case "physics" -> SystemTempletConstant.PHYSICS;
            case "chemistry" -> SystemTempletConstant.CHEMISTRY;
            case "biology" -> SystemTempletConstant.BIOLOGY;
            case "math", "maths", "mathematics" ->
                SystemTempletConstant.MATHEMATICS;
            case "history" -> SystemTempletConstant.HISTORY;
            case "geography" -> SystemTempletConstant.GEOGRAPHY;
            case "english", "hindi" ->
                SystemTempletConstant.LANGUAGE;
            default -> SystemTempletConstant.OTHER_SUBJECT;
        };
    }
}
