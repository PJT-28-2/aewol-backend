package com.aewol.common.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.length() > 20) {
            return false;
        }

        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasSpecialCharacter = false;

        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < '!' || character > '~') {
                return false;
            }
            if ((character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')) {
                hasLetter = true;
            } else if (character >= '0' && character <= '9') {
                hasDigit = true;
            } else if (isAsciiPunctuation(character)) {
                hasSpecialCharacter = true;
            }
        }

        int categoryCount = (hasLetter ? 1 : 0)
                + (hasDigit ? 1 : 0)
                + (hasSpecialCharacter ? 1 : 0);
        return categoryCount == 3 && value.length() >= 8
                || categoryCount == 2 && value.length() >= 10;
    }

    private boolean isAsciiPunctuation(char character) {
        return character >= '!' && character <= '/'
                || character >= ':' && character <= '@'
                || character >= '[' && character <= '`'
                || character >= '{' && character <= '~';
    }
}
