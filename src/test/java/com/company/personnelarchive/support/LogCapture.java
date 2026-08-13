package com.company.personnelarchive.support;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import java.util.List;

public final class LogCapture implements AutoCloseable {
    private final Logger logger;
    private final Level previousLevel;
    private final ListAppender<ILoggingEvent> appender;

    private LogCapture(Class<?> type) {
        logger = (Logger) LoggerFactory.getLogger(type);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.TRACE);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    public static LogCapture attach(Class<?> type) {
        return new LogCapture(type);
    }

    public List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    public String joined() {
        return String.join("\n", messages());
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }
}
