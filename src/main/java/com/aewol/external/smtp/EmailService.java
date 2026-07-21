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
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject("[애월] 이메일 인증 코드");
            helper.setText(
                    "<div style='padding: 20px; font-family: sans-serif;'>" +
                    "<h2 style='color: #1b2a49;'>애월 이메일 인증</h2>" +
                    "<p>아래 인증코드를 입력해주세요.</p>" +
                    "<div style='background: #f5f5f5; padding: 15px; font-size: 24px; " +
                    "letter-spacing: 5px; text-align: center; font-weight: bold;'>" +
                    code + "</div>" +
                    "<p style='color: #888; font-size: 12px;'>인증코드는 5분간 유효합니다.</p>" +
                    "</div>",
                    true
            );
            mailSender.send(message);
            log.info("인증 이메일 발송 완료: {}", to);
        } catch (MessagingException e) {
            log.error("이메일 발송 실패: {}", to, e);
            throw new RuntimeException("이메일 발송에 실패했습니다.", e);
        }
    }
}
