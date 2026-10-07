package com.sems.unit.controller;

import com.sems.common.BatchResult;
import com.sems.common.PageResult;
import com.sems.common.Result;
import com.sems.module.bag.controller.BagController;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BagControllerTest {

    @Test
    @DisplayName("袋台账与生命周期操作原样传参并维持统一响应结构")
    void delegatesBagOperations() {
        BagService service = mock(BagService.class);
        BagController controller = new BagController(service);
        String bagCode = "SJB-L-2609-00001-4";
        String requestId = "123e4567-e89b-12d3-a456-426614174000";
        CreateBagDto createDto = mock(CreateBagDto.class);
        BagQuery query = mock(BagQuery.class);
        ActivateDto activateDto = mock(ActivateDto.class);
        StockInDto stockInDto = mock(StockInDto.class);
        UnusedReturnDto unusedDto = mock(UnusedReturnDto.class);
        StockOutDto stockOutDto = mock(StockOutDto.class);
        RepairDto repairDto = mock(RepairDto.class);
        RepairCompleteDto completeDto = mock(RepairCompleteDto.class);
        ScrapDto scrapDto = mock(ScrapDto.class);
        LostRecoveryDto recoveryDto = mock(LostRecoveryDto.class);
        StatusCorrectDto correctionDto = mock(StatusCorrectDto.class);
        BagDetailVo detail = new BagDetailVo();
        BagInfoVo info = new BagInfoVo();
        PageResult<BagDetailVo> page = PageResult.of(List.of(detail), 1, 10);
        List<BagTraceVo> trace = List.of(new BagTraceVo());
        List<BagService.BatchItem> items = List.of(
                new BagService.BatchItem(bagCode, true, 0, "成功"),
                new BagService.BatchItem("SJB-M-2609-00002-1", false, 4102, "状态不允许"));
        when(service.create(createDto, requestId)).thenReturn(detail);
        when(service.page(query)).thenReturn(page);
        when(service.info(bagCode)).thenReturn(info);
        when(service.trace(bagCode)).thenReturn(trace);
        when(service.activate(bagCode, activateDto, requestId)).thenReturn(detail);
        when(service.stockIn(stockInDto, requestId)).thenReturn(items);
        when(service.stockOut(stockOutDto, requestId)).thenReturn(items);
        when(service.unusedReturn(bagCode, unusedDto, requestId)).thenReturn(detail);
        when(service.repair(bagCode, repairDto, requestId)).thenReturn(detail);
        when(service.repairComplete(bagCode, completeDto, requestId)).thenReturn(detail);
        when(service.scrap(bagCode, scrapDto, requestId)).thenReturn(detail);
        when(service.lostRecovery(bagCode, recoveryDto, requestId)).thenReturn(detail);
        when(service.statusCorrect(bagCode, correctionDto, requestId)).thenReturn(detail);

        assertSame(detail, controller.create(createDto, requestId).getData());
        assertSame(page, controller.page(query).getData());
        assertSame(info, controller.info(bagCode).getData());
        assertSame(trace, controller.trace(bagCode).getData());
        assertSame(detail, controller.activate(bagCode, activateDto, requestId).getData());
        assertSame(detail, controller.unusedReturn(bagCode, unusedDto, requestId).getData());
        assertSame(detail, controller.repair(bagCode, repairDto, requestId).getData());
        assertSame(detail, controller.repairComplete(bagCode, completeDto, requestId).getData());
        assertSame(detail, controller.scrap(bagCode, scrapDto, requestId).getData());
        assertSame(detail, controller.lostRecovery(bagCode, recoveryDto, requestId).getData());
        assertSame(detail, controller.statusCorrect(bagCode, correctionDto, requestId).getData());

        assertBatchEnvelope(controller.stockIn(stockInDto, requestId).getData(), items);
        assertBatchEnvelope(controller.stockOut(stockOutDto, requestId).getData(), items);
        verify(service).statusCorrect(bagCode, correctionDto, requestId);
        verify(service).repairComplete(bagCode, completeDto, requestId);
    }

    @Test
    @DisplayName("二维码 ZIP 与单袋 PNG 是声明式二进制例外并带正确响应头")
    void binaryDownloadsHaveContentHeadersAndExactBytes() {
        BagService service = mock(BagService.class);
        BagController controller = new BagController(service);
        byte[] zip = {1, 2, 3};
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47};
        when(service.batchGenerate(org.mockito.ArgumentMatchers.any(BatchGenerateDto.class),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(zip);
        when(service.qrCodePng("SJB-L-2609-00001-4")).thenReturn(png);

        ResponseEntity<byte[]> archive = controller.batchGenerate(new BatchGenerateDto(),
                "123e4567-e89b-12d3-a456-426614174000");
        assertEquals(HttpStatus.OK, archive.getStatusCode());
        assertSame(zip, archive.getBody());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM, archive.getHeaders().getContentType());
        assertEquals(zip.length, archive.getHeaders().getContentLength());
        String disposition = archive.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition.startsWith("attachment; filename=\"bag-qrcodes.zip\"; filename*=UTF-8''"));
        assertTrue(disposition.contains("%E5%BF%AB%E9%80%92%E8%A2%8B%E4%BA%8C%E7%BB%B4%E7%A0%81_"));

        ResponseEntity<byte[]> image = controller.qrcode("SJB-L-2609-00001-4");
        assertEquals(HttpStatus.OK, image.getStatusCode());
        assertSame(png, image.getBody());
        assertEquals(MediaType.IMAGE_PNG, image.getHeaders().getContentType());
    }

    private static void assertBatchEnvelope(BatchResult<BagService.BatchItem> result,
                                            List<BagService.BatchItem> expectedItems) {
        assertEquals(2, result.getTotal());
        assertEquals(1, result.getSuccess());
        assertEquals(1, result.getFailed());
        assertSame(expectedItems, result.getItems());
        assertEquals(4102, result.getItems().get(1).code());
    }
}
