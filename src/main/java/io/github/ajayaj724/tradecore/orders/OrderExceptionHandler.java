package io.github.ajayaj724.tradecore.orders;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OrderController.class)
class OrderExceptionHandler {

    @ExceptionHandler(UnknownSymbolException.class)
    ProblemDetail handleUnknownSymbol(UnknownSymbolException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Unknown symbol");
        return problem;
    }
}
