package dev.agentworkbench;

/**
 * 带 HTTP 状态的可操作错误。
 */
public class ApiException extends RuntimeException {
    private final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() { return status; }
}
