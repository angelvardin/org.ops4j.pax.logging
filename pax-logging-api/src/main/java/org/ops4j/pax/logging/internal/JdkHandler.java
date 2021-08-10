/*
 * Copyright 2006 Niclas Hedhman.
 *
 * Licensed  under the  Apache License,  Version 2.0  (the "License");
 * you may not use  this file  except in  compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed  under the  License is distributed on an "AS IS" BASIS,
 * WITHOUT  WARRANTIES OR CONDITIONS  OF ANY KIND, either  express  or
 * implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.logging.internal;

import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.ops4j.pax.logging.PaxLogger;
import org.ops4j.pax.logging.PaxLoggingConstants;
import org.ops4j.pax.logging.PaxLoggingManager;

/**
 * JUL {@link Handler} that bridges {@link LogRecord log records} to Pax Logging loggers.
 *
 * Even if {@link SimpleFormatter} is used, we only call its
 * {@link java.util.logging.Formatter#formatMessage(LogRecord)} which only uses
 * {@link java.text.MessageFormat#format(String, Object...)} method on log record's message.
 * It doesn't do anything with remaining fields of {@link LogRecord}.
 * <p>
 * Since the backport of TLSv1.3 in java (JDK-8248721) the the logic of TLS debug traces has changed. The TLS log records contain
 * an additional string parameter. They are without a format parameter in the log message. The {@link JdkHandler} appends them
 * to the end of the TLS log message.
 * <p>
 * The {@link JdkHandler} has the following logging modes for the TLS log records:<br>
 *  - no logging:  TLS messages are not logged.<br>
 *  - no hex dumps logging: All messages except hex dumps are logged.<br>
 *  - debug logging: All messages are logged.<br>
 *  The logging mode is determined by the value of the <b>org.ops4j.pax.logging.tlsdebug.loggingMode</b> system property.
 */
public class JdkHandler extends Handler {

    private static final String FQCN = java.util.logging.Logger.class.getName();
    private static final String TLS_DEBUG_LOGGER = "javax.net.ssl";
    private static final String NO_LOGGING_MODE = "no_logging";
    private static final String DEBUG_LOGGING_MODE = "debug_logging";
    private static final String HEX_DUMP_OFFSET = "0000:";

    private PaxLoggingManager m_loggingManager;

    public JdkHandler(PaxLoggingManager loggingManager) {
        m_loggingManager = loggingManager;
        setFormatter(new SimpleFormatter());
    }

    @Override
    public void close() throws SecurityException {
    }

    @Override
    public void flush() {
    }

    /**
     * Using information from {@link LogRecord} logging method on related {@link PaxLogger} is called.
     * @param record
     */
    @Override
    public void publish(LogRecord record) {
        Level level = record.getLevel();
        String loggerName = record.getLoggerName();
        PaxLogger logger = m_loggingManager.getLogger(loggerName, FQCN);

        if (TLS_DEBUG_LOGGER.equals(loggerName) && !isTLSDebugLoggingEnabled()) {
            return;
        }

        String message;
        try {
            if (TLS_DEBUG_LOGGER.equals(loggerName) && isTLSDebugLoggingEnabled()) {
                // The TLS debug log records contain an additional string parameter with dump attachments.
                // They are without a format parameter in the log message so they are not parsed by the JUL formatter.
                message = getTLSLogMessage(record);
            } else {
                // LogRecord may have parameters associated, so let's format the message
                // using JUL formatter
                message = getFormatter().formatMessage(record);
            }
        } catch (Exception ex) {
            message = record.getMessage();
        }

        Throwable throwable = record.getThrown();
        int levelInt = level.intValue();
        if (throwable != null) {
            if (levelInt <= Level.FINER.intValue()) {
                logger.trace(message, throwable);
            } else if (levelInt <= Level.FINE.intValue()) {
                logger.debug(message, throwable);
            } else if (levelInt <= Level.INFO.intValue()) {
                logger.info(message, throwable);
            } else if (levelInt <= Level.WARNING.intValue()) {
                logger.warn(message, throwable);
            } else {
                logger.error(message, throwable);
            }
        } else {
            if (levelInt <= Level.FINER.intValue()) {
                logger.trace(message);
            } else if (levelInt <= Level.FINE.intValue()) {
                logger.debug(message);
            } else if (levelInt <= Level.INFO.intValue()) {
                logger.info(message);
            } else if (levelInt <= Level.WARNING.intValue()) {
                logger.warn(message);
            } else {
                logger.error(message);
            }
        }
    }



    private String getTLSLogMessage(LogRecord logRecord) {
        String message;
        try {
            message = logRecord.getMessage();
            if (logRecord.getParameters() != null) {
                String tlsLoggingModeProperty = System.getProperty(PaxLoggingConstants.LOGGING_CFG_TLS_LOGGING_MODE);
                boolean isDebugLoggingEnabled = DEBUG_LOGGING_MODE.equals(tlsLoggingModeProperty);

                String logParameters = Stream.of(logRecord.getParameters())
                        .filter(Objects::nonNull)
                        .filter(String.class::isInstance)
                        .map(String::valueOf)
                        // hex dump parameters are not filtered only if the TLS logging mode is DEBUG_LOGGING
                        .filter(x -> !isHexDumpMessage(x) || isDebugLoggingEnabled)
                        .collect(Collectors.joining(System.lineSeparator()));

                logParameters = logParameters != null && !logParameters.trim().isEmpty() ? System.lineSeparator() + logParameters : "";
                message += logParameters;
            }
        } catch (Exception ex) {
            message = logRecord.getMessage();
        }

        return message;
    }

    /**
     * Checks if TLS debug logging is enabled.
     *
     * @return  Returns <b>true</b> if the value of the system property <b>com.seeburger.tlsdebug.LOGGING_MODE</b> is not blank and
     *          the TLS logging mode is not <b>NO_LOGGING_MODE</b>.
     */
    private boolean isTLSDebugLoggingEnabled() {
        String property = System.getProperty(PaxLoggingConstants.LOGGING_CFG_TLS_LOGGING_MODE);
        return property != null && !property.trim().isEmpty() && !NO_LOGGING_MODE.equals(property);
    }

    /**
     * Checks whether the TLS log parameter is hex dump message.
     *
     * @param message The TLS log parameter.
     * @return  If the parameter starts with <b>0000:</b> returns true. Otherwise returns false.
     */
    private boolean isHexDumpMessage(String message) {
        return message.startsWith(HEX_DUMP_OFFSET, 2);
    }
}
