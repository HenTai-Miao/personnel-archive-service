/* 异常诊断摘要：保留类型和代码位置，但排除可能携带业务值的异常消息。 */
package com.company.personnelarchive.common;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.StringJoiner;

public final class SafeExceptionSummary {
    private static final int MAX_CAUSES = 5;
    private static final int MAX_FRAMES = 12;
    private static final int MAX_IDENTIFIER_LENGTH = 160;

    private SafeExceptionSummary() {}

    public static String describe(Throwable throwable) {
        if (throwable == null) return "exceptionType=unknown causeTypes=none stack=none";
        return "exceptionType=" + throwable.getClass().getName()
            + " causeTypes=" + causeTypes(throwable)
            + " stack=" + stack(throwable);
    }

    private static String causeTypes(Throwable throwable) {
        StringJoiner types = new StringJoiner(",");
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(throwable);
        Throwable current = throwable.getCause();
        int count = 0;
        while (current != null && count < MAX_CAUSES && seen.add(current)) {
            types.add(current.getClass().getName());
            current = current.getCause();
            count++;
        }
        return types.length() == 0 ? "none" : types.toString();
    }

    private static String stack(Throwable throwable) {
        StringJoiner frames = new StringJoiner("|");
        StackTraceElement[] elements = throwable.getStackTrace();
        for (int index = 0; index < Math.min(elements.length, MAX_FRAMES); index++) {
            StackTraceElement element = elements[index];
            frames.add(safeIdentifier(element.getClassName()) + "#"
                + safeIdentifier(element.getMethodName()) + ":" + element.getLineNumber());
        }
        return frames.length() == 0 ? "none" : frames.toString();
    }

    private static String safeIdentifier(String value) {
        if (value == null || value.isBlank()) return "unknown";
        String safe = value.replaceAll("[^A-Za-z0-9_.$<>-]", "_");
        return safe.substring(0, Math.min(safe.length(), MAX_IDENTIFIER_LENGTH));
    }
}
