package com.sems.common;

/**
 * 系统版本号（全站唯一出处）。
 *
 * <p>与 {@code pom.xml} 的 {@code project.version} 保持一致，随版本发布同步递增；
 * 供启动日志、接口文档标注使用。注意：存活探针 {@code /api/health} 刻意不返回版本号
 * （见《系统设计说明书》9.1 信息泄露对策），排查问题请查服务端日志，不要把它加进探针响应。
 */
public final class SemsVersion {

    /** 当前版本号（v0.1.0，对应《系统设计说明书》2.4 版本基线）。 */
    public static final String VERSION = "0.1.0";

    private SemsVersion() {
    }
}
