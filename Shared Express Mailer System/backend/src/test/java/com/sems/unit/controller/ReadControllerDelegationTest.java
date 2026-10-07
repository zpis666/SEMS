package com.sems.unit.controller;

import com.sems.common.PageResult;
import com.sems.common.Result;
import com.sems.common.HealthController;
import com.sems.module.audit.controller.AuditQueryController;
import com.sems.module.audit.dto.AuditLogQuery;
import com.sems.module.audit.dto.AuditLogVo;
import com.sems.module.audit.dto.LoginLogQuery;
import com.sems.module.audit.dto.LoginLogVo;
import com.sems.module.audit.service.AuditQueryService;
import com.sems.module.order.controller.BorrowController;
import com.sems.module.order.controller.OrderController;
import com.sems.module.order.dto.BorrowDto;
import com.sems.module.order.dto.BorrowVo;
import com.sems.module.order.dto.LostConfirmDto;
import com.sems.module.order.dto.OrderDetailVo;
import com.sems.module.order.dto.OrderQuery;
import com.sems.module.order.dto.OrderVo;
import com.sems.module.order.dto.ReturnMarkDto;
import com.sems.module.order.dto.ShippingDto;
import com.sems.module.order.service.BorrowService;
import com.sems.module.order.service.OrderService;
import com.sems.module.stats.controller.AlertController;
import com.sems.module.stats.controller.StatsController;
import com.sems.module.stats.dto.AlertQuery;
import com.sems.module.stats.dto.AlertVo;
import com.sems.module.stats.dto.DashboardVo;
import com.sems.module.stats.dto.OrderTrendVo;
import com.sems.module.stats.dto.StockDistributionVo;
import com.sems.module.stats.dto.TurnoverVo;
import com.sems.module.stats.service.AlertService;
import com.sems.module.stats.service.StatsService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReadControllerDelegationTest {

    @Test
    @DisplayName("探活返回固定且不含依赖状态等内部指纹")
    void healthHasOnlyUpStatus() {
        Result<Map<String, String>> response = new HealthController().health();
        assertEquals(0, response.getCode());
        assertEquals(Map.of("status", "UP"), response.getData());
        assertEquals(1, response.getData().size());
    }

    @Test
    @DisplayName("看板查询把可选天数和查询结果原样映射进统一响应")
    void statsControllersForwardFiltersAndData() {
        StatsService stats = mock(StatsService.class);
        StatsController controller = new StatsController(stats);
        DashboardVo dashboard = new DashboardVo();
        List<OrderTrendVo> trend = List.of(new OrderTrendVo());
        List<TurnoverVo> turnover = List.of(new TurnoverVo());
        StockDistributionVo distribution = new StockDistributionVo();
        when(stats.dashboard()).thenReturn(dashboard);
        when(stats.orderTrend(14)).thenReturn(trend);
        when(stats.bagTurnover(30)).thenReturn(turnover);
        when(stats.stockDistribution()).thenReturn(distribution);

        assertSame(dashboard, controller.dashboard().getData());
        assertSame(trend, controller.orderTrend(14).getData());
        assertSame(turnover, controller.bagTurnover(30).getData());
        assertSame(distribution, controller.stockDistribution().getData());
        verify(stats).orderTrend(14);
        verify(stats).bagTurnover(30);
    }

    @Test
    @DisplayName("告警列表传递同一筛选对象并保留分页结果")
    void alertQueryIsPassedThrough() {
        AlertService service = mock(AlertService.class);
        AlertController controller = new AlertController(service);
        AlertQuery query = new AlertQuery();
        PageResult<AlertVo> page = PageResult.of(List.of(new AlertVo()), 1, 10);
        when(service.pageAlerts(query)).thenReturn(page);

        Result<PageResult<AlertVo>> response = controller.listAlerts(query);
        assertEquals(0, response.getCode());
        assertSame(page, response.getData());
        verify(service).pageAlerts(query);
    }

    @Test
    @DisplayName("审计列表与完整 IP 查询传递筛选、记录 ID 和请求上下文")
    void auditControllerForwardsQueriesAndRequest() {
        AuditQueryService service = mock(AuditQueryService.class);
        AuditQueryController controller = new AuditQueryController(service);
        AuditLogQuery auditQuery = new AuditLogQuery();
        LoginLogQuery loginQuery = new LoginLogQuery();
        HttpServletRequest request = mock(HttpServletRequest.class);
        PageResult<AuditLogVo> auditPage = PageResult.of(List.of(new AuditLogVo()), 1, 10);
        PageResult<LoginLogVo> loginPage = PageResult.of(List.of(new LoginLogVo()), 1, 10);
        when(service.pageAuditLogs(auditQuery)).thenReturn(auditPage);
        when(service.pageLoginLogs(loginQuery)).thenReturn(loginPage);
        when(service.viewAuditLogIp(7L, request)).thenReturn("192.0.2.7");
        when(service.viewLoginLogIp(8L, request)).thenReturn("2001:db8::8");

        assertSame(auditPage, controller.listAuditLogs(auditQuery).getData());
        assertSame(loginPage, controller.listLoginLogs(loginQuery).getData());
        assertEquals("192.0.2.7", controller.viewAuditLogIp(7L, request).getData());
        assertEquals("2001:db8::8", controller.viewLoginLogIp(8L, request).getData());
        verify(service).viewAuditLogIp(7L, request);
        verify(service).viewLoginLogIp(8L, request);
    }

    @Test
    @DisplayName("订单查询区分本人/平台筛选，写动作传递订单号、DTO 与幂等头")
    void orderControllerForwardsEachOperation() {
        OrderService service = mock(OrderService.class);
        OrderController controller = new OrderController(service);
        OrderQuery query = new OrderQuery();
        PageResult<OrderVo> page = PageResult.of(List.of(new OrderVo()), 1, 10);
        OrderDetailVo detail = new OrderDetailVo();
        ReturnMarkDto returnDto = new ReturnMarkDto();
        LostConfirmDto lostDto = new LostConfirmDto();
        when(service.pageMine(query)).thenReturn(page);
        when(service.pageAll(query)).thenReturn(page);
        when(service.pageTimeout(query)).thenReturn(page);
        when(service.detail("ORD-9")).thenReturn(detail);

        assertSame(page, controller.mine(query).getData());
        assertSame(page, controller.list(query).getData());
        assertSame(page, controller.timeout(query).getData());
        assertSame(detail, controller.detail("ORD-9").getData());
        assertNull(controller.returnMark("ORD-9", returnDto, "request-1").getData());
        assertNull(controller.lostConfirm("ORD-9", lostDto, "request-2").getData());
        verify(service).returnMark("ORD-9", returnDto, "request-1");
        verify(service).lostConfirm("ORD-9", lostDto, "request-2");
    }

    @Test
    @DisplayName("取袋动作族原样传递申领/寄件参数与幂等头，撤回返回空数据")
    void borrowControllerForwardsActionPayloads() {
        BorrowService service = mock(BorrowService.class);
        BorrowController controller = new BorrowController(service);
        BorrowDto borrowDto = new BorrowDto();
        ShippingDto shippingDto = new ShippingDto();
        BorrowVo borrow = new BorrowVo();
        OrderDetailVo order = new OrderDetailVo();
        when(service.borrow(borrowDto, "request-1")).thenReturn(borrow);
        when(service.shipping("ORD-10", shippingDto, "request-2")).thenReturn(order);

        assertSame(borrow, controller.borrow(borrowDto, "request-1").getData());
        assertSame(order, controller.shipping("ORD-10", shippingDto, "request-2").getData());
        assertNull(controller.withdraw("ORD-10", "request-3").getData());
        verify(service).withdraw("ORD-10", "request-3");
    }
}
