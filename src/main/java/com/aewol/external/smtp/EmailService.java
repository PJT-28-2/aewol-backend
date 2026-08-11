package com.aewol.external.smtp;

import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    public void sendVerificationEmail(String to, String code) {
        sendCodeEmail(to, code, "[애월] 이메일 인증 코드", "애월 이메일 인증");
    }

    public void sendPasswordResetEmail(String to, String code) {
        sendCodeEmail(to, code, "[애월] 비밀번호 재설정 인증 코드", "애월 비밀번호 재설정");
    }

    private void sendCodeEmail(String to, String code, String subject, String heading) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(
                    "<div style='padding: 20px; font-family: sans-serif;'>" +
                    "<h2 style='color: #1b2a49;'>" + heading + "</h2>" +
                    "<p>아래 인증코드를 입력해주세요.</p>" +
                    "<div style='background: #f5f5f5; padding: 15px; font-size: 24px; " +
                    "letter-spacing: 5px; text-align: center; font-weight: bold;'>" +
                    code + "</div>" +
                    "<p style='color: #888; font-size: 12px;'>인증코드는 5분간 유효합니다.</p>" +
                    "</div>",
                    true
            );
            mailSender.send(message);
            log.info("인증 이메일 발송이 완료되었습니다.");
        } catch (MessagingException e) {
            log.error("인증 이메일 발송에 실패했습니다.", e);
            throw new RuntimeException("이메일 발송에 실패했습니다.", e);
        }
    }
}
