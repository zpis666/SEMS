package com.sems.module.bag.controller;

import com.sems.aspect.AuditLog;
import com.sems.common.BatchResult;
import com.sems.common.Constants;
import com.sems.common.PageResult;
import com.sems.common.Result;
import com.sems.module.bag.dto.ActivateDto;
import com.sems.module.bag.dto.BagDetailVo;
import com.sems.module.bag.dto.BagInfoVo;
import com.sems.module.bag.dto.BagQuery;
import com.sems.module.bag.dto.BagTraceVo;
import com.sems.module.bag.dto.BatchGenerateDto;
import com.sems.module.bag.dto.CreateBagDto;
import com.sems.module.bag.dto.LostRecoveryDto;
import com.sems.module.bag.dto.RepairCompleteDto;
import com.sems.module.bag.dto.RepairDto;
import com.sems.module.bag.dto.ScrapDto;
import com.sems.module.bag.dto.StatusCorrectDto;
import com.sems.module.bag.dto.StockInDto;
import com.sems.module.bag.dto.StockOutDto;
import com.sems.module.bag.dto.UnusedReturnDto;
import com.sems.module.bag.service.BagService;
import com.sems.security.RequireRole;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 快递袋管理接口（M4，见《系统设计说明书》5.4 接口表；端点、权限列逐条对应）。
 *
 * <p>约定：除两个声明式例外（批量生成 ZIP 与二维码 PNG 返回二进制流）外，一律返回
 * {@code Result<T>} 统一 JSON；垂直越权由 {@link RequireRole} 拦截，水平越权（本站/代操作）
 * 在 Service 层校验（设计书 4.4 与契约模块版第 6 节）。写接口从请求头
 * {@code clientRequestId} 取幂等 ID（UUID），由 Service 统一校验格式。
 *
 * <p>审计：关键事件用 {@link AuditLog} 标注（报废/状态修正为重点核查项，见设计书 7.6）；
 * 「维修完成」接口中走 #14 报废的分支另由 Service 补记 action="报废" 的审计，
 * 使报废可按动作名独立检索（注解是静态的，一个端点只能有一个 action）。
 */
@RestController
@RequestMapping("/api/v1/bags")
public class BagController {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final BagService bagService;

    public BagController(BagService bagService) {
        this.bagService = bagService;
    }

    /** 新建袋档案（平台管理员；编码自动生成、初始空闲）。 */
    @PostMapping
    @RequireRole("PLATFORM_ADMIN")
    @AuditLog(module = "快递袋管理", action = "新建袋")
    public Result<BagDetailVo> create(@Valid @RequestBody CreateBagDto dto,
                                      @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                      String clientRequestId) {
        return Result.ok(bagService.create(dto, clientRequestId));
    }

    /** 批量生成袋档案并下载二维码压缩包（平台管理员；整批原子、≤ sems.batch.max-size）。 */
    @PostMapping("/batch-generate")
    @RequireRole("PLATFORM_ADMIN")
    @AuditLog(module = "快递袋管理", action = "批量生成")
    public ResponseEntity<byte[]> batchGenerate(@Valid @RequestBody BatchGenerateDto dto,
                                                @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                                String clientRequestId) {
        byte[] zip = bagService.batchGenerate(dto, clientRequestId);
        return attachmentResponse(zip, "快递袋二维码_" + LocalDateTime.now().format(FILE_TIME) + ".zip");
    }

    /** 袋台账分页查询（站点/平台管理员；站管限本站袋）。 */
    @GetMapping
    @RequireRole("STATION_ADMIN")
    public Result<PageResult<BagDetailVo>> page(BagQuery query) {
        return Result.ok(bagService.page(query));
    }

    /** 扫码查询袋信息（登录用户；最小必要集 + 归属扩展，见设计书 7.4）。 */
    @GetMapping("/{bagCode}/info")
    @RequireRole("USER")
    public Result<BagInfoVo> info(@PathVariable String bagCode) {
        return Result.ok(bagService.info(bagCode));
    }

    /** 单只袋二维码 PNG（站点/平台管理员；站管限本站袋，标签补打用）。 */
    @GetMapping("/{bagCode}/qrcode")
    @RequireRole("STATION_ADMIN")
    public ResponseEntity<byte[]> qrcode(@PathVariable String bagCode) {
        byte[] png = bagService.qrCodePng(bagCode);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    /** 袋全生命周期流转日志（站点/平台管理员；站管限本站袋）。 */
    @GetMapping("/{bagCode}/trace")
    @RequireRole("STATION_ADMIN")
    public Result<List<BagTraceVo>> trace(@PathVariable String bagCode) {
        return Result.ok(bagService.trace(bagCode));
    }

    /** 激活上架（转移 #1；站管按袋的登记站点校验归属，平台管理员代操作）。 */
    @PostMapping("/{bagCode}/activate")
    @RequireRole("STATION_ADMIN")
    @AuditLog(module = "快递袋管理", action = "激活上架")
    public Result<BagDetailVo> activate(@PathVariable String bagCode,
                                        @Valid @RequestBody(required = false) ActivateDto dto,
                                        @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                        String clientRequestId) {
        return Result.ok(bagService.activate(bagCode, dto, clientRequestId));
    }

    /** 上架入库（转移 #11，批量逐袋部分成功；复用次数超限的袋返回 4108；返回统一批量信封）。 */
    @PostMapping("/stock-in")
    @RequireRole("STATION_ADMIN")
    @AuditLog(module = "快递袋管理", action = "上架入库")
    public Result<BatchResult<BagService.BatchItem>> stockIn(@Valid @RequestBody StockInDto dto,
                                                             @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                                             String clientRequestId) {
        List<BagService.BatchItem> items = bagService.stockIn(dto, clientRequestId);
        // 判成功依据：BatchItem 为不可变 record，没有 isSuccess()/getSuccess() 方法（record 访问器为
        // success()）；其构造约定「成功 ⇔ code == 0」——成功项恒写 0，失败项写 BizException 的错误码
        // （见 BagService.stockIn 的逐袋分支），故以 code == 0 判定。
        // 统一信封：逐袋明细固定挂 items 键（契约模块版第 5 节），Service 返回形态保持不变。
        return Result.ok(BatchResult.of(items, item -> item.code() == 0));
    }

    /** 未使用回收（转移 #4；绑定订单须已取消或不存在）。 */
    @PostMapping("/{bagCode}/unused-return")
    @RequireRole("STATION_ADMIN")
    @AuditLog(module = "快递袋管理", action = "未使用回收")
    public Result<BagDetailVo> unusedReturn(@PathVariable String bagCode,
                                            @Valid @RequestBody(required = false) UnusedReturnDto dto,
                                            @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                            String clientRequestId) {
        return Result.ok(bagService.unusedReturn(bagCode, dto, clientRequestId));
    }

    /** 出库登记（批量逐袋部分成功；仅报废袋实物出库与经批准的盘点差异出库；返回统一批量信封）。 */
    @PostMapping("/stock-out")
    @RequireRole("STATION_ADMIN")
    @AuditLog(module = "快递袋管理", action = "出库")
    public Result<BatchResult<BagService.BatchItem>> stockOut(@Valid @RequestBody StockOutDto dto,
                                                              @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                                              String clientRequestId) {
        List<BagService.BatchItem> items = bagService.stockOut(dto, clientRequestId);
        // 判成功依据同 stock-in：BatchItem 为不可变 record 且无 isSuccess()/getSuccess() 方法，
        // 按构造约定「成功 ⇔ code == 0」（成功/幂等回放恒为 0，失败写 BizException 错误码）判定；
        // 逐袋明细统一挂 items 键（契约模块版第 5 节）。
        return Result.ok(BatchResult.of(items, item -> item.code() == 0));
    }

    /** 维修登记（转移 #12；破损描述必填）。 */
    @PostMapping("/{bagCode}/repair")
    @RequireRole("STATION_ADMIN")
    @AuditLog(module = "快递袋管理", action = "维修登记")
    public Result<BagDetailVo> repair(@PathVariable String bagCode,
                                      @Valid @RequestBody RepairDto dto,
                                      @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                      String clientRequestId) {
        return Result.ok(bagService.repair(bagCode, dto, clientRequestId));
    }

    /** 维修完成（转移 #13/#14；无法修复或复用超限转报废，Service 补记 action="报废" 审计）。 */
    @PostMapping("/{bagCode}/repair-complete")
    @RequireRole("STATION_ADMIN")
    @AuditLog(module = "快递袋管理", action = "维修完成")
    public Result<BagDetailVo> repairComplete(@PathVariable String bagCode,
                                              @Valid @RequestBody RepairCompleteDto dto,
                                              @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                              String clientRequestId) {
        return Result.ok(bagService.repairComplete(bagCode, dto, clientRequestId));
    }

    /** 直接报废登记（转移 #15；审计重点核查项，action="直接报废"）。 */
    @PostMapping("/{bagCode}/scrap")
    @RequireRole("STATION_ADMIN")
    @AuditLog(module = "快递袋管理", action = "直接报废")
    public Result<BagDetailVo> scrap(@PathVariable String bagCode,
                                     @Valid @RequestBody ScrapDto dto,
                                     @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                     String clientRequestId) {
        return Result.ok(bagService.scrap(bagCode, dto, clientRequestId));
    }

    /** 遗失找回（转移 #20，平台管理员；说明与找回站点必填）。 */
    @PostMapping("/{bagCode}/lost-recovery")
    @RequireRole("PLATFORM_ADMIN")
    @AuditLog(module = "快递袋管理", action = "遗失找回")
    public Result<BagDetailVo> lostRecovery(@PathVariable String bagCode,
                                            @Valid @RequestBody LostRecoveryDto dto,
                                            @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                            String clientRequestId) {
        return Result.ok(bagService.lostRecovery(bagCode, dto, clientRequestId));
    }

    /** 状态修正（转移 #21，平台管理员；目标态白名单与修正原因必填；审计重点核查项）。 */
    @PostMapping("/{bagCode}/status-correct")
    @RequireRole("PLATFORM_ADMIN")
    @AuditLog(module = "快递袋管理", action = "状态修正")
    public Result<BagDetailVo> statusCorrect(@PathVariable String bagCode,
                                             @Valid @RequestBody StatusCorrectDto dto,
                                             @RequestHeader(name = Constants.CLIENT_REQUEST_ID_HEADER, required = false)
                                             String clientRequestId) {
        return Result.ok(bagService.statusCorrect(bagCode, dto, clientRequestId));
    }

    /**
     * 附件下载响应（batch-generate 声明式例外，见设计书 6.2）：
     * 文件名用 URLEncoder 以 UTF-8 编码后放 RFC 5987 的 {@code filename*}，并附 ASCII 兜底名。
     */
    private static ResponseEntity<byte[]> attachmentResponse(byte[] body, String filename) {
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"bag-qrcodes.zip\"; filename*=UTF-8''" + encoded);
        headers.setContentLength(body.length);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }
}
