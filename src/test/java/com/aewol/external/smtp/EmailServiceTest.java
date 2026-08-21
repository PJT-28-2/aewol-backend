package com.aewol.external.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

class EmailServiceTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final EmailService emailService = new EmailService(mailSender);

    @Test
    void messagingExceptionIsNormalized() throws Exception {
        MimeMessage message = mock(MimeMessage.class);
        MessagingException cause = new MessagingException("sensitive provider detail");
        when(mailSender.createMimeMessage()).thenReturn(message);
        doThrow(cause).when(message).setRecipient(eq(Message.RecipientType.TO), any(Address.class));

        EmailSendException exception = assertThrows(EmailSendException.class,
                () -> emailService.sendVerificationEmail("user@example.com", "123456"));

        assertEquals("이메일 발송에 실패했습니다.", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void mailExceptionIsNormalized() {
        MimeMessage message = mock(MimeMessage.class);
        MailSendException cause = new MailSendException("sensitive provider detail");
        when(mailSender.createMimeMessage()).thenReturn(message);
        doThrow(cause).when(mailSender).send(message);

        EmailSendException exception = assertThrows(EmailSendException.class,
                () -> emailService.sendPasswordResetEmail("user@example.com", "123456"));

        assertEquals("이메일 발송에 실패했습니다.", exception.getMessage());
        assertSame(cause, exception.getCause());
    }
}
