package com.search;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.book.BookSearchDownloadApplication;

@SpringBootTest(classes = BookSearchDownloadApplication.class, properties = {
		"spring.datasource.url=jdbc:h2:mem:book-context;DB_CLOSE_DELAY=-1",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"spring.jpa.show-sql=false",
		"spring.autoconfigure.exclude=org.springframework.ai.vectorstore.mariadb.autoconfigure.MariaDbStoreAutoConfiguration",
		"spring.ai.ollama.init.pull-model-strategy=never",
		"debug=false"
})
class BookSearchDownloadApplicationTests {

	@Test
	void contextLoads() {
	}

}
