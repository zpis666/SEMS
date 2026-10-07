package com.sems.unit.job;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sems.job.StatsDailyJob;
import com.sems.module.bag.mapper.BagMapper;
import com.sems.module.order.mapper.BagOrderMapper;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.StationMapper;
import com.sems.module.stats.entity.StatsDaily;
import com.sems.module.stats.mapper.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class DailyStatisticsJobTest {
 @org.junit.jupiter.api.BeforeAll static void metadata(){com.sems.unit.support.MybatisTestMetadata.init();}
 @Test void aggregationWritesPlatformAndEachStationAndPadsEightStates(){StatsDailyMapper daily=mock(StatsDailyMapper.class);StationMapper stations=mock(StationMapper.class);BagOrderMapper orders=mock(BagOrderMapper.class);BagMapper bags=mock(BagMapper.class);StatsQueryMapper stats=mock(StatsQueryMapper.class);Station s=new Station();s.setId(1L);when(stations.selectList(any(Wrapper.class))).thenReturn(List.of(s));when(orders.selectMaps(any(Wrapper.class))).thenReturn(List.of(Map.of("stationId",1L,"cnt",4L)));when(orders.countFinishedByStation(any(),any())).thenReturn(List.of(Map.of("stationId",1L,"cnt",2L)));when(stats.countInCirculationByStation()).thenReturn(List.of(Map.of("stationId",1L,"cnt",4L)));when(bags.selectMaps(any(Wrapper.class))).thenReturn(List.of(Map.of("stationId",1L,"status","IDLE","cnt",3L)));when(stats.countBagByStatus()).thenReturn(List.of(Map.of("status","IDLE","cnt",3L)));new StatsDailyJob(daily,stations,orders,bags,stats).aggregateDaily();ArgumentCaptor<StatsDaily> c=ArgumentCaptor.forClass(StatsDaily.class);verify(daily,times(2)).insert(c.capture());StatsDaily platform=c.getAllValues().get(0);assertEquals(0L,platform.getStationId());assertEquals(4,platform.getOrdersBorrow());assertEquals(2,platform.getOrdersReturn());assertEquals(0.5,platform.getTurnoverRate());assertTrue(platform.getStockJson().contains("TO_DISINFECT"));assertFalse(platform.getStockJson().contains("REUSABLE"));}
 @Test void repeatedDayUpdatesInsteadOfDuplicateInsertAndEmptyDenominatorIsZero(){StatsDailyMapper daily=mock(StatsDailyMapper.class);StatsDaily old=new StatsDaily();old.setId(9L);when(daily.selectOne(any(Wrapper.class))).thenReturn(old);new StatsDailyJob(daily,mock(StationMapper.class),mock(BagOrderMapper.class),mock(BagMapper.class),mock(StatsQueryMapper.class)).aggregateDaily();verify(daily,never()).insert(any(StatsDaily.class));verify(daily).updateById(argThat((StatsDaily d)->d.getId()==9L&&d.getTurnoverRate()==0));}
 @Test void queryFailureDoesNotEscapeScheduler(){BagOrderMapper orders=mock(BagOrderMapper.class);when(orders.selectMaps(any(Wrapper.class))).thenThrow(new IllegalStateException("数据库抖动"));assertDoesNotThrow(()->new StatsDailyJob(mock(StatsDailyMapper.class),mock(StationMapper.class),orders,mock(BagMapper.class),mock(StatsQueryMapper.class)).aggregateDaily());}
}
