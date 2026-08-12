package com.aewol.common.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aewol.domain.auth.dto.PasswordResetRequest;
import com.aewol.domain.auth.dto.SignupRequest;
import com.aewol.domain.member.dto.MemberPasswordChangeRequest;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class PasswordValidatorTest {

    private final PasswordValidator validator = new PasswordValidator();

    @Test
    void acceptsBoundaryLengthsForTwoAndThreeCharacterCategories() {
        assertTrue(validator.isValid("Abcdef1!", null));
        assertTrue(validator.isValid("abcdefgh12", null));
        assertTrue(validator.isValid("Abcdefghijklmnopq1!@", null));
        assertTrue(validator.isValid("abcdefgh1!", null));
        assertTrue(validator.isValid("abcdefghijklmnopqr1!", null));
    }

    @Test
    void rejectsTooShortSingleCategoryTooLongBlankAndNullPasswords() {
        assertFalse(validator.isValid("Abcde1!", null));
        assertFalse(validator.isValid("abcdefgh1", null));
        assertFalse(validator.isValid("abcdefghij", null));
        assertFalse(validator.isValid("abcdefghijklmnopqrs1!", null));
        assertFalse(validator.isValid("   ", null));
        assertFalse(validator.isValid(null, null));
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

    private void assertValidPasswordAnnotation(Field field) {
        assertNotNull(field.getAnnotation(ValidPassword.class));
    }
}
