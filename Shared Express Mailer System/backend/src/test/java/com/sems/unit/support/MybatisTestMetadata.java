package com.sems.unit.support;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.sems.module.bag.entity.Bag;
import com.sems.module.order.entity.BagOrder;
import com.sems.module.station.entity.*;
import com.sems.module.user.entity.*;
import com.sems.module.stats.entity.StatsDaily;
/** 无Spring容器单元测试初始化MyBatis lambda元数据，不连接数据库。 */
public final class MybatisTestMetadata {
 private MybatisTestMetadata(){}
 public static synchronized void init(){for(Class<?> entity:new Class<?>[]{Bag.class,BagOrder.class,Station.class,AllocationOrder.class,SysUser.class,SysRole.class,SysUserRole.class,StatsDaily.class})if(TableInfoHelper.getTableInfo(entity)==null)TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"单元测试"),entity);}
}
