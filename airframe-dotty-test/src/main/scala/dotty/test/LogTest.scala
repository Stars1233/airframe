package dotty.test

import wvlet.log.{LogFormatter, LogLevel, LogSupport, Logger}

object LogTest extends LogSupport {

  def run: Unit = {
    // Save the original log level to restore it later
    val originalLogLevel = Logger.getDefaultLogLevel
    try {
      // Logger.setDefaultFormatter(LogFormatter.SourceCodeLogFormatter)
      info("Hello airframe-log")
      debug("Hello airframe-log")
      error("Hello airframe-log")
      warn("Hello airframe-log")
      trace("Hello airframe-log")

      logger.info("direct log")
      logger.debug("direct log")
      logger.trace("direct log")
      logger.warn("direct log")
      logger.error("direct log")

      logger.setLogLevel(LogLevel.TRACE)
      logger.info("direct log")
      logger.debug("direct log")
      logger.trace("direct log")
      logger.warn("direct log")
      logger.error("direct log")

      logger.setLogLevel(LogLevel.WARN)
      info("Hello airframe-log")
      debug("Hello airframe-log")
      error("Hello airframe-log")
      warn("Hello airframe-log")
      trace("Hello airframe-log")

      warn("exception log test", new IllegalArgumentException("invalid arg"))

      Logger.setDefaultLogLevel(LogLevel.INFO)
    } finally {
      // Restore the original log level to avoid affecting other tests
      Logger.setDefaultLogLevel(originalLogLevel)
    }
  }
}
