package com.aewol.common.util;

public final class PhoneNumberUtil {

    private PhoneNumberUtil() {
    }

    public static String normalize(String phone) {
        return phone == null ? null : phone.replaceAll("[^0-9]", "");
    }
}
