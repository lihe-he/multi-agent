package dev.agentworkbench;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/**
 * 客户端错误可读，内部异常不泄露堆栈或执行环境。
 */
@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException exception) { return ResponseEntity.status(exception.status()).body(Json.obj("error", exception.getMessage(), "status", exception.status())); }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception exception) { return ResponseEntity.badRequest().body(Json.obj("error", "请求格式或参数无效", "status", 400)); }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception exception) { return ResponseEntity.internalServerError().body(Json.obj("error", "操作未完成，请查看任务记录或核心日志", "status", 500)); }
}
