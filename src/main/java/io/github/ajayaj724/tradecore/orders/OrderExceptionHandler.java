package io.github.ajayaj724.tradecore.orders;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {OrderController.class, CancelRequestController.class})
class OrderExceptionHandler {

    @ExceptionHandler(CancelRequestConflictException.class)
    ProblemDetail handleCancelRequestConflict(CancelRequestConflictException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Cancel request conflict");
        return problem;
    }

    @ExceptionHandler(CancelRequestNotFoundException.class)
    ProblemDetail handleCancelRequestNotFound(CancelRequestNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Cancel request not found");
        return problem;
    }

    @ExceptionHandler(UnknownSymbolException.class)
    ProblemDetail handleUnknownSymbol(UnknownSymbolException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        problem.setTitle("Unknown symbol");
        return problem;
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail handleNotFound(OrderNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Order not found");
        return problem;
    }

    @ExceptionHandler(OrderNotCancellableException.class)
    ProblemDetail handleNotCancellable(OrderNotCancellableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Order not cancellable");
        return problem;
    }
}
