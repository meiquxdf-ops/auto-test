package com.atest.common;

import java.util.regex.Pattern;

/**
 * displayTag 字符集与 {@code deploy/install.sh --tag} 对齐：字母数字和 {@code . _ -}，长度 1–64。
 * 运维台改名、hello 注册、安装脚本必须是同一套规则，否则 UI 能起的名字脚本装不上。
 */
public final class DisplayTags {

    public static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    public static final String RULE = "只允许字母数字和 . _ -，长度 1-64";

    private DisplayTags() {
    }

    public static boolean isValid(String tag) {
        return tag != null && PATTERN.matcher(tag).matches();
    }

    /** HTTP 路径：非法 tag 直接 400。 */
    public static String requireValidHttp(String tag) {
        String t = tag == null ? "" : tag.trim();
        if (!isValid(t)) {
            throw ApiException.badRequest("displayTag " + RULE);
        }
        return t;
    }

    /** TCP hello：非法用 IllegalArgumentException，由会话层回 bad_request。 */
    public static String requireValidHello(String tag) {
        String t = tag == null ? "" : tag.trim();
        if (!isValid(t)) {
            throw new IllegalArgumentException("displayTag " + RULE + ": " + tag);
        }
        return t;
    }
}
