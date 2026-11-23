package com.book.upload.controller;

import org.junit.jupiter.api.Test;

import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import com.book.upload.controller.impl.UploadFileControllerImpl;
import com.book.upload.service.FileUploadService;
import com.book.dto.BookExtractionResult;
import com.book.entity.AuthorDetails;
import com.book.entity.BookDetails;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UploadFileControllerTests {

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
			.withUserConfiguration(UploadWebConfiguration.class);

	@Test
	void discoversUploadControllerAndAcceptsMultipartFile() {
		contextRunner.run(context -> {
			MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
			MockMultipartFile file = new MockMultipartFile("file", "book.pdf", "application/pdf",
					"Book content".getBytes(java.nio.charset.StandardCharsets.UTF_8));

			mvc.perform(multipart("/api/upload-file").file(file))
					.andExpect(handler().handlerType(UploadFileControllerImpl.class))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.fileName").value("book.pdf"))
					.andExpect(jsonPath("$.extraction.bookDetails.bookName").value("Test book"))
					.andExpect(jsonPath("$.status").value("SUCCESS"))
					.andExpect(jsonPath("$.fileUploadDateTime").isNotEmpty());
		});
	}

	@Test
	void rejectsUploadWithoutFilePart() {
		contextRunner.run(context -> {
			MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
			mvc.perform(multipart("/api/upload-file"))
					.andExpect(handler().handlerType(UploadFileControllerImpl.class))
					.andExpect(status().isBadRequest());
		});
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableWebMvc
	@ComponentScan(basePackageClasses = UploadFileControllerImpl.class)
	static class UploadWebConfiguration {
		@Bean
		FileUploadService fileUploadService() {
			return file -> {
				BookDetails book = new BookDetails();
				book.setBookName("Test book");
				return new BookExtractionResult(new AuthorDetails(), book, List.of());
			};
		}
	}
}
