package com.sems.common;

/**
 * 统一响应体（见《系统设计说明书》6.2）。
 *
 * <pre>{ "code": 0, "message": "success", "data": { } }</pre>
 *
 * <p>约定：{@code code=0} 表示成功；非 0 为错误码（见 {@link ErrorCode}）。
 * 分页数据放在 {@code data.records / data.total / data.pages}，见 {@link PageResult}。
 *
 * <p>唯一例外：{@code batch-generate} 的二维码 ZIP 下载为二进制流响应（不套本结构）。
 *
 * @param <T> 业务数据类型
 */
public class Result<T> {

    private int code;
    private String message;
    private T data;

    public Result() {
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> Result<T> ok() {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 使用自定义提示文案（如把字段错误、具体原因回给前端），错误码不变。 */
    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.getCode(), message, null);
    }

    /** 携带明细数据的失败响应（如 3001 的字段级错误清单）。 */
    public static <T> Result<T> fail(ErrorCode errorCode, String message, T data) {
        return new Result<>(errorCode.getCode(), message, data);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
