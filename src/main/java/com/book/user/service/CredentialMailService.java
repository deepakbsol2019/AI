package com.book.user.service;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/** Link delivery never logs messages, addresses, credentials or token-bearing URLs. */
@Service
public class CredentialMailService {
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<com.book.tools.ServiceToolAccess> toolAccess;

    @org.springframework.ai.tool.annotation.Tool(name = "credentialDeliveryReadiness", description = "Check whether local credential email delivery is configured for the signed-in user's support query. This checks configuration only; it does not send a message, test SMTP connectivity, or reveal mail settings.")
    public String credentialDeliveryReadiness() {
        toolAccess.getObject().require("GET", "/api/user/profile/details");
        try {
            requireAvailable();
            return "Credential email delivery is configured. Actual delivery and SMTP connectivity have not been tested.";
        } catch (ResponseStatusException unavailable) {
            return "Credential email delivery is unavailable. Ask an administrator to review its configuration.";
        }
    }

    private final ObjectProvider<JavaMailSender> senders;
    private final Environment environment;
    private final TemplateEngine templates;
    public CredentialMailService(ObjectProvider<JavaMailSender> senders,Environment environment) {
        this.senders=senders; this.environment=environment;
        ClassLoaderTemplateResolver resolver=new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/user/");resolver.setSuffix(".txt");
        resolver.setTemplateMode(TemplateMode.TEXT);resolver.setCharacterEncoding("UTF-8");resolver.setCacheable(true);
        templates=new TemplateEngine();templates.setTemplateResolver(resolver);
    }
    public void requireAvailable() {
        if (!environment.getProperty("app.user.mail.enabled",Boolean.class,false) || senders.getIfAvailable() == null
                || environment.getProperty("app.mail.from","").isBlank()) unavailable();
    }
    public void send(String email,String purpose,String secret,String tenantCode,Duration lifetime) {
        requireAvailable();
        String base=environment.getProperty("app.user.public-base-url","http://localhost:4200").replaceAll("/$","");
        URI uri=URI.create(base);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())
                && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost())))) unavailable();
        String path=switch (purpose) {
            case "SET_PASSWORD" -> "/user/password/setup";
            case "PASSWORD_RESET" -> "/user/password/reset";
            case "SSO_ENROLLMENT" -> "/user/enroll";
            default -> throw new IllegalArgumentException("Unsupported credential message.");
        };
        String link=base+path+"#token="+secret+(tenantCode == null ? "" : "&tenantCode="
                +java.net.URLEncoder.encode(tenantCode,java.nio.charset.StandardCharsets.UTF_8));
        SimpleMailMessage message=new SimpleMailMessage();
        message.setFrom(environment.getProperty("app.mail.from"));
        message.setTo(email);
        String action="SSO_ENROLLMENT".equals(purpose) ? "enroll your company identity"
                : "PASSWORD_RESET".equals(purpose) ? "reset your password" : "set your password";
        message.setSubject("Bookhaven: "+action);
        Context context=new Context(java.util.Locale.ROOT);
        context.setVariable("action",action);
        context.setVariable("minutes",lifetime.toMinutes());context.setVariable("link",link);
        message.setText(templates.process("credential-link",context));
        try { senders.getObject().send(message); }
        catch (MailException failure) { unavailable(); }
    }
    private static void unavailable() { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Email delivery is unavailable. Please try again after the administrator configures it."); }
}
