package io.github.ajayaj724.tradecore.config;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** MVC exceptions render RFC 9457 via ResponseEntityExceptionHandler; domain handlers land in Plan 1B. */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {}
