package com.aewol.common.util;

public final class EmailMaskingUtil {

    private EmailMaskingUtil() {
    }

    public static String mask(String email) {
        if (email == null) {
            return "****";
        }
        int at = email.lastIndexOf('@');
        if (at <= 0 || at == email.length() - 1 || email.indexOf('@') != at) {
            return "****";
        }
        String localPart = email.substring(0, at);
        String visible = localPart.substring(0, Math.min(localPart.length() >= 4 ? 4 : 1, localPart.length()));
        return visible + "****" + email.substring(at);
    }
}
