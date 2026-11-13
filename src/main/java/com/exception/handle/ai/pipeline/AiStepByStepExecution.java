package com.exception.handle.ai.pipeline;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import com.exception.handle.ai.model.ExceptionAnalysis;

@Service
public class AiStepByStepExecution {

	private final ChatClient chatClient;
	
	public AiStepByStepExecution(ChatClient chatClient) {
		this.chatClient=chatClient;
	}
	
	public ExceptionAnalysis getAiExceptionFix(Exception exception) {
		if(null==exception)
			return new ExceptionAnalysis();
		System.out.println("before ::"+exception);
		StringWriter writer = new StringWriter();
        exception.printStackTrace(new PrintWriter(writer));
        System.out.println("After ::"+exception);
        
        
        String message = limit(
                Objects.toString(exception.getMessage(), "(no message)"),
                2_000
        );

        String stackTrace = limit(writer.toString(), 12_000);

        
		ExceptionAnalysis aiResponse = chatClient.prompt()
        .user(user -> user
                .text("""
                    Analyze the following exception.

                    Application: StudentFeeService

                    Exception type:
                    {exceptionType}

                    Exception message:
                    {exceptionMessage}

                    Stack trace:
                    {stackTrace}

                    Source code: not provided.
                    Build/Git commit: not provided.
                    """)
                .param(
                        "exceptionType",
                        exception.getClass().getName()
                )
                .param("exceptionMessage", message)
                .param("stackTrace", stackTrace)
        )
        .call()
        .entity(ExceptionAnalysis.class);
        
		try {
			validate(aiResponse);
		}catch (Exception e) {
			return new ExceptionAnalysis();
		}
		return aiResponse;
	}
	private static void validate(ExceptionAnalysis analysis) {

        if (analysis == null
                || analysis.getCategory() == null
                || analysis.getProbableCause() == null
                || analysis.getProbableCause().isBlank()
                || analysis.getSuggestedFix() == null
                || analysis.getSuggestedFix().isBlank()
                || analysis.getNeedsSourceCode() == null) {

            throw new IllegalStateException(
                    "AI returned an incomplete exception analysis"
            );
        }
    }

	private static String limit(String value, int maxLength) {

        if (value.length() <= maxLength) {
            return value;
        }

        return value.substring(0, maxLength)
                + "\n[diagnostic text truncated]";
    }
	
}
