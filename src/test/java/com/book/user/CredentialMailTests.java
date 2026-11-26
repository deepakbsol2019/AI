package com.book.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;
import com.book.user.service.CredentialMailService;

class CredentialMailTests {
    @Test void disabledEmailRequiresNoSenderAndReportsRealUnavailability() {
        var beans=new DefaultListableBeanFactory();
        var service=new CredentialMailService(beans.getBeanProvider(JavaMailSender.class),new MockEnvironment());
        assertThatThrownBy(service::requireAvailable).isInstanceOf(ResponseStatusException.class).hasMessageContaining("unavailable");
    }
    @Test void rendersClasspathPlainTextTemplateWithOneTimeFragmentLinkAndConfiguredSender() {
        JavaMailSender sender=mock(JavaMailSender.class);
        var service=service(sender,new MockEnvironment());
        service.send("person@example.com","SSO_ENROLLMENT","one-time-secret","company_code",Duration.ofMinutes(30));
        ArgumentCaptor<SimpleMailMessage> message=ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getFrom()).isEqualTo("library@example.com");
        assertThat(message.getValue().getText()).contains("https://books.example.com/user/enroll#token=one-time-secret&tenantCode=company_code", "30 minutes", "ignore this email");
        assertThat(message.getValue().getSubject()).doesNotContain("one-time-secret");
    }
    @Test void deliveryErrorsDoNotExposeTokenBearingSmtpDetailsAndUnsafeBasesAreRejected() {
        JavaMailSender sender=mock(JavaMailSender.class);
        doThrow(new MailSendException("sensitive raw token-bearing message")).when(sender).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> service(sender,new MockEnvironment()).send("person@example.com","SET_PASSWORD","secret",null,Duration.ofHours(24)))
                .isInstanceOf(ResponseStatusException.class).hasMessageNotContaining("sensitive raw").hasNoCause();
        var unsafe=service(mock(JavaMailSender.class),new MockEnvironment().withProperty("app.user.public-base-url","http://public.example.com"));
        assertThatThrownBy(() -> unsafe.send("person@example.com","RESET_PASSWORD","secret",null,Duration.ofMinutes(30))).isInstanceOf(ResponseStatusException.class);
    }
    @Test void resetRendersTrustedHttpsLinkWithTheResetActionAndLifetime() {
        var sender=mock(JavaMailSender.class);
        service(sender,new MockEnvironment()).send("registered@example.com","PASSWORD_RESET","secret",null,Duration.ofMinutes(15));
        var message=ArgumentCaptor.forClass(SimpleMailMessage.class);verify(sender).send(message.capture());
        assertThat(message.getValue().getTo()).containsExactly("registered@example.com");
        assertThat(message.getValue().getSubject()).contains("reset your password");
        assertThat(message.getValue().getText()).contains("https://books.example.com/user/password/reset#token=secret","15 minutes");
    }
    private CredentialMailService service(JavaMailSender sender,MockEnvironment environment) {
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("mailSender",sender);
        environment.withProperty("app.user.mail.enabled","true").withProperty("app.mail.from","library@example.com");
        if (!environment.containsProperty("app.user.public-base-url")) environment.withProperty("app.user.public-base-url","https://books.example.com");
        return new CredentialMailService(beans.getBeanProvider(JavaMailSender.class),environment);
    }
}
