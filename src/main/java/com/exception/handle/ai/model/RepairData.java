package com.exception.handle.ai.model;

import java.nio.file.Path;
import java.util.List;

public final class RepairData {

	private RepairData() {}
	
	    public record SourceSnapshot(Path file, String className, String method,
	                                 int line, String source) {}
	
	    public record ContextFile(String relativePath, String role, String source) {}
	
	    public record RepairInput(String className, String method, int line,
	                              String exceptionType, String stackTrace,
	                              String source, List<ContextFile> relatedContext) {}
	
	    public record PatchProposal(Boolean changeRequired, String reason,
	                                String correctedSource) {}
	
	    public record ApplyReceipt(Path backup, Path record,
	                               boolean indexed, String warning) {}
	
	    public record RepairResult(String status, String message,
	                               String backupPath, String historyPath) {
	        public static RepairResult of(String status, String message) {
	            return new RepairResult(status, message, null, null);
	        }
	    }
}
