package com.aewol.common.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aewol.domain.auth.dto.PasswordResetRequest;
import com.aewol.domain.auth.dto.SignupRequest;
import com.aewol.domain.member.dto.MemberPasswordChangeRequest;
import java.lang.reflect.Field;
import java.util.Set;
import java.util.stream.Collectors;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PasswordValidatorTest {

    private final PasswordValidator validator = new PasswordValidator();
    private final Validator beanValidator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsBoundaryLengthsForTwoAndThreeCharacterCategories() {
        assertTrue(validator.isValid("Abcdef1!", null));
        assertTrue(validator.isValid("abcdefgh12", null));
        assertTrue(validator.isValid("Abcdefghijklmnopq1!@", null));
        assertTrue(validator.isValid("abcdefgh1!", null));
        assertTrue(validator.isValid("abcdefghijklmnopqr1!", null));
    }

    @Test
    void rejectsInvalidFormatsAndDelegatesRequiredValuesToNotBlank() {
        assertFalse(validator.isValid("Abcde1!", null));
        assertFalse(validator.isValid("abcdefgh1", null));
        assertFalse(validator.isValid("abcdefghij", null));
        assertFalse(validator.isValid("abcdefghijklmnopqrs1!", null));
        assertTrue(validator.isValid("   ", null));
        assertTrue(validator.isValid(null, null));
    }

    @Test
    void recognizesOnlyAsciiPunctuationAsSpecialCharacters() {
        assertTrue(validator.isValid("Abcdef1!", null));

        for (char punctuation : new char[] {'!', '/', ':', '@', '[', '`', '{', '~'}) {
            assertTrue(validator.isValid("Abcdef1" + punctuation, null));
        }
    }

    @Test
    void rejectsCharactersOutsidePrintableAsciiWithoutSpaces() {
        assertFalse(validator.isValid("abc123한글", null));
        assertFalse(validator.isValid("abc123  ", null));
        assertFalse(validator.isValid("abc123😀", null));
        assertFalse(validator.isValid("abc123한글가나", null));
        assertFalse(validator.isValid("abc123    ", null));
        assertFalse(validator.isValid("Abcd123!한", null));
        assertFalse(validator.isValid("Abcd123!\t", null));
        assertFalse(validator.isValid("Abcd123!\n", null));
    }

    @Test
    void usesSamePolicyAnnotationForEveryPasswordCreationFlow() throws Exception {
        assertValidPasswordAnnotation(SignupRequest.class.getDeclaredField("password"));
        assertValidPasswordAnnotation(MemberPasswordChangeRequest.class.getDeclaredField("newPassword"));
        assertValidPasswordAnnotation(PasswordResetRequest.class.getDeclaredField("newPassword"));
    }

    @Test
    void delegatesNullAndBlankValidationToNotBlankWithoutDuplicateViolations() {
        assertOnlyNotBlankViolation(new SignupRequest(), "password", null);
        assertOnlyNotBlankViolation(new SignupRequest(), "password", "   ");
        assertOnlyNotBlankViolation(new MemberPasswordChangeRequest(), "newPassword", null);
        assertOnlyNotBlankViolation(new MemberPasswordChangeRequest(), "newPassword", "   ");
        assertOnlyNotBlankViolation(new PasswordResetRequest(), "newPassword", null);
        assertOnlyNotBlankViolation(new PasswordResetRequest(), "newPassword", "   ");
    }

    @Test
    void validatesPasswordPolicyThroughBeanValidationOnActualDtos() {
        assertNoPasswordViolation(new SignupRequest(), "password", "Abcdef1!");
        assertNoPasswordViolation(new MemberPasswordChangeRequest(), "newPassword", "abcdefgh12");
        assertNoPasswordViolation(new PasswordResetRequest(), "newPassword", "Abcdef1!");

        assertValidPasswordViolation(new PasswordResetRequest(), "newPassword", "abcdefghij");
        assertValidPasswordViolation(new PasswordResetRequest(), "newPassword", "abc123\uD55C\uAE00\uAC00\uB098");
        assertValidPasswordViolation(new PasswordResetRequest(), "newPassword", "abc123    ");
        assertValidPasswordViolation(new PasswordResetRequest(), "newPassword", "abc123\uD83D\uDE00abcd");
    }

    private void assertValidPasswordAnnotation(Field field) {
        assertNotNull(field.getAnnotation(ValidPassword.class));
    }

    private void assertOnlyNotBlankViolation(Object request, String fieldName, String value) {
        Set<ConstraintViolation<Object>> violations = validateField(request, fieldName, value);

        assertEquals(1, violations.size());
        assertEquals(NotBlank.class,
                violations.iterator().next().getConstraintDescriptor().getAnnotation().annotationType());
    }

    private void assertNoPasswordViolation(Object request, String fieldName, String value) {
        assertTrue(validateField(request, fieldName, value).isEmpty());
    }

    private void assertValidPasswordViolation(Object request, String fieldName, String value) {
        assertTrue(validateField(request, fieldName, value).stream()
                .anyMatch(violation -> violation.getConstraintDescriptor().getAnnotation().annotationType()
                        == ValidPassword.class));
    }

    private Set<ConstraintViolation<Object>> validateField(Object request, String fieldName, String value) {
        ReflectionTestUtils.setField(request, fieldName, value);
        return beanValidator.validate(request).stream()
                .filter(violation -> violation.getPropertyPath().toString().equals(fieldName))
                .collect(Collectors.toSet());
    }
}
