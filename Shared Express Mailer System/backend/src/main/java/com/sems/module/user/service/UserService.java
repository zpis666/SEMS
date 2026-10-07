package com.sems.module.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sems.common.BizException;
import com.sems.common.Constants;
import com.sems.common.ErrorCode;
import com.sems.common.PageResult;
import com.sems.common.util.MaskUtils;
import com.sems.infra.RedisKeys;
import com.sems.module.station.entity.Station;
import com.sems.module.station.mapper.StationMapper;
import com.sems.module.user.dto.ChangePasswordDto;
import com.sems.module.user.dto.CreateUserDto;
import com.sems.module.user.dto.RoleVo;
import com.sems.module.user.dto.UpdateProfileDto;
import com.sems.module.user.dto.UpdateRolesDto;
import com.sems.module.user.dto.BindStationDto;
import com.sems.module.user.dto.UpdateStatusDto;
import com.sems.module.user.dto.UserDetailVo;
import com.sems.module.user.dto.UserInfoVo;
import com.sems.module.user.dto.UserQuery;
import com.sems.module.user.entity.SysRole;
import com.sems.module.user.entity.SysUser;
import com.sems.module.user.entity.SysUserRole;
import com.sems.module.user.mapper.SysRoleMapper;
import com.sems.module.user.mapper.SysUserMapper;
import com.sems.module.user.mapper.SysUserRoleMapper;
import com.sems.security.AuthContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户服务：本人信息维护与平台管理员侧的账号管理（见《系统设计说明书》5.1 用户管理模块、4.2 权限模型）。
 *
 * <p>三条贯穿本类的口径：
 * <ul>
 *   <li><b>输出最小化</b>：本人信息手机号脱敏；仅平台管理员用户管理接口返回完整登录手机号；
 *       密码摘要只进不出，绝不落入 VO（9.5）；</li>
 *   <li><b>会话吊销</b>：角色变更、站点绑定变更、禁用、重置密码、本人改密后，
 *       {@code sys_user.token_version} 自增 1 并删除 Redis 读缓存 {@code auth:userver:{uid}}
 *       （7.1「两级主动失效」，旧令牌下一次请求即被拒绝）；</li>
 *   <li><b>本人数据范围</b>：{@code /users/me} 系列只以 {@link AuthContext#userId()} 定位数据，
 *       不接收任何前端传入的用户标识（4.4 防水平越权）。</li>
 * </ul>
 *
 * <p>角色存储为 {@code sys_user_role} 多行关联；出参中的单值 {@code role} 取最高等级角色码，
 * 与 JWT 单角色快照（{@code LoginUser.roleLevel()}）同一套口径（4.3）。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** 分页默认每页条数（契约 10：默认 10）。 */
    private static final long DEFAULT_PAGE_SIZE = 10L;

    /** 分页每页条数上限（契约 10：最大 100）。 */
    private static final long MAX_PAGE_SIZE = 100L;

    /**
     * 重置密码后的初始口令：与设计书 4.2 预置平台管理员口令同款口径，
     * 重置同时置 {@code must_change_password=1} 强制本人首次登录后修改。
     */
    private static final String RESET_INITIAL_PASSWORD = "Sems@2026";

    /** 角色等级优先级（高→低，与 {@code LoginUser.roleLevel()} 一致）：多角色时取最高者对外呈现。 */
    private static final List<String> ROLE_PRIORITY = List.of(
            Constants.ROLE_PLATFORM_ADMIN, Constants.ROLE_STATION_ADMIN, Constants.ROLE_USER);

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final StationMapper stationMapper;
    private final BCryptPasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;

    public UserService(SysUserMapper sysUserMapper,
                       SysRoleMapper sysRoleMapper,
                       SysUserRoleMapper sysUserRoleMapper,
                       StationMapper stationMapper,
                       BCryptPasswordEncoder passwordEncoder,
                       StringRedisTemplate redis) {
        this.sysUserMapper = sysUserMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.stationMapper = stationMapper;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
    }

    // ------------------------------------------------------------------
    // 本人（登录用户）
    // ------------------------------------------------------------------

    /**
     * 个人信息（{@code GET /api/v1/users/me}，5.1）：手机号脱敏展示。
     */
    public UserInfoVo getMe() {
        Long userId = AuthContext.userId();
        return toInfoVo(requireUser(userId), listRoleCodes(userId));
    }

    /**
     * 修改昵称等非敏感字段（{@code PATCH /api/v1/users/me}，5.1）。
     *
     * <p>不含敏感字段（手机号/角色/状态/密码各有专门接口），故不触发令牌吊销；
     * JWT 中的昵称快照最迟在续签时刷新。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserInfoVo updateProfile(UpdateProfileDto dto) {
        Long userId = AuthContext.userId();
        SysUser user = requireUser(userId);
        String nickname = dto.getNickname().trim();
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getNickname, nickname));
        user.setNickname(nickname);
        return toInfoVo(user, listRoleCodes(userId));
    }

    /**
     * 修改密码（{@code POST /api/v1/users/me/password}，5.1）：校验旧密码 → 写入新摘要 →
     * 清除强制改密标记 → 自增 token_version（全部旧令牌立即失效，须重新登录；7.1）。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserInfoVo changePassword(ChangePasswordDto dto) {
        Long userId = AuthContext.userId();
        SysUser user = requireUser(userId);
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            // 旧密码错误属参数层面的身份确认失败，归 3001（6.4）
            throw new BizException(ErrorCode.PARAM_INVALID, "原密码错误");
        }
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getPassword, passwordEncoder.encode(dto.getNewPassword()))
                .set(SysUser::getMustChangePassword, 0));
        invalidateTokens(userId);
        // 出参用内存对象：仅更新被改动的字段，避免再查一次（密码不在 VO 中，安全）
        user.setMustChangePassword(0);
        return toInfoVo(user, listRoleCodes(userId));
    }

    // ------------------------------------------------------------------
    // 平台管理员
    // ------------------------------------------------------------------

    /**
     * 创建账号并授予角色（{@code POST /api/v1/users}，5.1 / FR-M1-12）。
     *
     * <p>流程：手机号唯一性预校验（唯一索引 {@code uk_user_phone} 兜住并发窗口，冲突转 3001
     * “手机号已注册”）→ 角色码校验 → 站点管理员必须绑定已存在站点 → BCrypt 加密密码 →
     * 写 {@code sys_user} 与 {@code sys_user_role}。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserDetailVo createUser(CreateUserDto dto) {
        String phone = dto.getPhone().trim();
        Long exists = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getPhone, phone));
        if (exists != null && exists > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "手机号已注册");
        }

        List<SysRole> roles = resolveRoles(dto.getRoles());
        Long stationId = null;
        if (containsStationAdmin(roles)) {
            if (dto.getStationId() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "站点管理员必须绑定站点");
            }
            requireStation(dto.getStationId());
            stationId = dto.getStationId();
        }
        // 非站点管理员忽略传入的 stationId：DB 口径为「仅站点管理员非空」（8.2）

        SysUser user = new SysUser();
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname().trim());
        user.setStatus(Constants.STATUS_ENABLED);
        user.setStationId(stationId);
        user.setTokenVersion(0);
        user.setMustChangePassword(0);
        try {
            sysUserMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // 并发创建同手机号：唯一索引是最终防线，转成与预校验一致的业务提示
            throw new BizException(ErrorCode.PARAM_INVALID, "手机号已注册");
        }
        insertUserRoles(user.getId(), roles);
        return loadDetail(user.getId());
    }

    /**
     * 调整角色（{@code PUT /api/v1/users/{id}/roles}，5.1 / FR-M1-13）。
     *
     * <p>全量替换 {@code sys_user_role}（先删后插，同一事务）；最终角色含 {@code STATION_ADMIN}
     * 则站点必填（本请求的 stationId 优先，缺省沿用现有绑定），不含则站点绑定置空——
     * 保证「仅站点管理员非空」的库内口径不被角色调整破坏。变更后自增 token_version。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserDetailVo updateRoles(Long userId, UpdateRolesDto dto) {
        SysUser user = requireUser(userId);
        List<SysRole> roles = resolveRoles(dto.getRoles());

        Long stationId = null;
        if (containsStationAdmin(roles)) {
            stationId = dto.getStationId() != null ? dto.getStationId() : user.getStationId();
            if (stationId == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "站点管理员必须绑定站点（可在本请求携带 stationId）");
            }
            requireStation(stationId);
        }

        // 全量替换：先删旧关联再插新关联，同一事务保证「要么全换、要么不动」
        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        insertUserRoles(userId, roles);

        // station_id 需要支持置空：updateById 会忽略 null 字段，故用 UpdateWrapper 显式 SET
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getStationId, stationId));

        invalidateTokens(userId);
        return loadDetail(userId);
    }

    /**
     * 调整站点绑定（{@code PUT /api/v1/users/{id}/station}，5.1 / FR-M1-13）。
     *
     * <p>站点管理员的 {@code stationId} 由此写入并随 JWT 携带（4.2，JWT 中该声明的唯一来源）；
     * 目标账号须已具备 {@code STATION_ADMIN} 角色（一岗一站，否则绑定无意义）；
     * 换绑后自增 token_version，新令牌才会携带新站点身份。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserDetailVo updateStation(Long userId, BindStationDto dto) {
        requireUser(userId);
        if (!listRoleCodes(userId).contains(Constants.ROLE_STATION_ADMIN)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该账号不是站点管理员，无需绑定站点");
        }
        requireStation(dto.getStationId());
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getStationId, dto.getStationId()));
        invalidateTokens(userId);
        return loadDetail(userId);
    }

    /**
     * 分页查询用户（{@code GET /api/v1/users}，5.1）：支持手机号模糊、角色、状态筛选，
     * 出参含角色列表与站点名。手机号出参脱敏（9.5）。
     */
    public PageResult<UserDetailVo> pageUsers(UserQuery query) {
        long pageNum = query.getPageNum() == null || query.getPageNum() < 1 ? 1L : query.getPageNum();
        // 契约 10：pageSize 默认 10、最大 100；超出收敛到上限而非报错，防大页拖垮查询
        long pageSize = query.getPageSize() == null || query.getPageSize() < 1
                ? DEFAULT_PAGE_SIZE
                : Math.min(query.getPageSize(), MAX_PAGE_SIZE);

        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getPhone())) {
            wrapper.like(SysUser::getPhone, query.getPhone().trim());
        }
        if (StringUtils.hasText(query.getStatus())) {
            wrapper.eq(SysUser::getStatus, query.getStatus().trim());
        }
        if (StringUtils.hasText(query.getRole())) {
            SysRole role = sysRoleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                    .eq(SysRole::getRoleCode, query.getRole().trim()));
            if (role == null) {
                // 未定义的角色码：直接回空页，避免把客户端筛选值带进 SQL
                return PageResult.of(List.of(), 0L, pageSize);
            }
            // 角色筛选需经关联表联查：{0} 为 MyBatis-Plus 参数占位（预编译绑定，非字符串拼接，见 9.4）
            wrapper.apply("id IN (SELECT user_id FROM sys_user_role WHERE role_id = {0})", role.getId());
        }
        applyOrderBy(wrapper, query.getOrderBy());

        Page<SysUser> page = sysUserMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(composeDetails(page.getRecords()), page.getTotal(), pageSize);
    }

    /**
     * 启用/禁用账号（{@code PATCH /api/v1/users/{id}/status}，5.1 / FR-M1-16）。
     *
     * <p>禁用触发 token_version 自增，「封号即失效」在下一次请求兑现（7.1）；启用不吊销。
     * 禁止禁用当前登录账号：否则平台可能当场失去自己的管理入口。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserDetailVo updateStatus(Long userId, UpdateStatusDto dto) {
        requireUser(userId);
        boolean disabling = Constants.STATUS_DISABLED.equals(dto.getStatus());
        if (disabling && userId.equals(AuthContext.userId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不能禁用当前登录账号");
        }
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getStatus, dto.getStatus()));
        if (disabling) {
            invalidateTokens(userId);
        }
        return loadDetail(userId);
    }

    /**
     * 重置密码（{@code POST /api/v1/users/{id}/password-reset}，5.1）。
     *
     * <p>口令重置为初始口令并置 {@code must_change_password=1}（响应携带 mustChangePassword=true），
     * 同时自增 token_version 吊销该账号全部会话（4.2「被重置密码后强制改密」）。
     */
    @Transactional(rollbackFor = Exception.class)
    public UserInfoVo resetPassword(Long userId) {
        requireUser(userId);
        sysUserMapper.update(null, new LambdaUpdateWrapper<SysUser>()
                .eq(SysUser::getId, userId)
                .set(SysUser::getPassword, passwordEncoder.encode(RESET_INITIAL_PASSWORD))
                .set(SysUser::getMustChangePassword, 1));
        invalidateTokens(userId);
        // 重新读取，保证出参与库内一致（must_change_password 已置 1）
        return toInfoVo(requireUser(userId), listRoleCodes(userId));
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 取用户，不存在抛 3001「用户不存在」。
     * 校验顺序遵循「先资源存在性，后归属」（契约模块版第 6 节）；平台管理员为全局数据范围，
     * 用户管理接口不存在站点归属概念，故无需二次归属比对。
     */
    private SysUser requireUser(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "用户不存在");
        }
        return user;
    }

    /**
     * 解析并校验角色码列表：去重保序 → 查 {@code sys_role} → 任一码未定义即拒绝。
     * 去重是为避免重复插入撞 {@code uk_user_role_user_id_role_id} 唯一键。
     */
    private List<SysRole> resolveRoles(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "至少选择一个角色");
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String code : roleCodes) {
            if (code == null || code.isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "角色码不能为空");
            }
            codes.add(code.trim());
        }
        List<SysRole> roles = sysRoleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .in(SysRole::getRoleCode, codes));
        if (roles.size() != codes.size()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "包含未定义的角色码");
        }
        return roles;
    }

    /** 最终角色是否含站点管理员。 */
    private boolean containsStationAdmin(List<SysRole> roles) {
        return roles.stream().anyMatch(role -> Constants.ROLE_STATION_ADMIN.equals(role.getRoleCode()));
    }

    /** 站点存在性校验：不存在抛 3001（站点主数据由站点管理模块维护）。 */
    private Station requireStation(Long stationId) {
        Station station = stationMapper.selectById(stationId);
        if (station == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "站点不存在");
        }
        return station;
    }

    /** 全量写入用户-角色关联行。 */
    private void insertUserRoles(Long userId, List<SysRole> roles) {
        for (SysRole role : roles) {
            SysUserRole link = new SysUserRole();
            link.setUserId(userId);
            link.setRoleId(role.getId());
            sysUserRoleMapper.insert(link);
        }
    }

    /**
     * 全量吊销某账号的令牌（7.1「两级主动失效」第 2 条）：
     * {@code sys_user.token_version + 1}，随后删除 Redis 读缓存 {@code auth:userver:{uid}}。
     *
     * <p><b>删除时机必须等事务提交</b>：若在提交前 DEL，被吊销账号的并发请求可能在删除后、
     * 本事务提交前读到旧快照（旧 token_version + ENABLED）并回写缓存，使「封号即失效」
     * 最多延迟一个缓存 TTL（60s）。注册 {@code afterCommit} 同步器后，删除发生在提交完成时点：
     * 提交前的回写无论先后都被这次删除覆盖，剩余竞态窗口收窄为「提交后仍在途的旧读回写」
     * 这一极小概率情形，最坏仍有 60s TTL 兜底（7.1「自增后同步 DEL 使缓存立即失效」）。
     *
     * <p>缓存删除失败只记 WARN 不上抛：Redis 降级期拦截器本就是「直读 DB 比对，保持即时失效」，
     * 缓存只是 60s 读加速；若让缓存异常把 DB 已生效的吊销回滚，反而破坏「禁用即失效」。
     */
    private void invalidateTokens(Long userId) {
        sysUserMapper.incrementTokenVersion(userId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // 事务同步激活（本方法全部调用方都是 @Transactional）：把 DEL 挂到提交之后执行
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteUserVerCache(userId);
                }
            });
        } else {
            // 防御性分支：脱离事务上下文时无同步器可注册，只能立即删（语义与旧实现一致）
            deleteUserVerCache(userId);
        }
    }

    /** 删除 token_version 读缓存；失败仅记 WARN（拦截器会回退 DB 比对，缓存最迟 60s 自然过期）。 */
    private void deleteUserVerCache(Long userId) {
        try {
            redis.delete(RedisKeys.authUserVer(userId));
        } catch (RuntimeException e) {
            log.warn("令牌版本缓存删除失败，拦截器将以 DB 为准（缓存最迟 60s 自然过期）：uid={}", userId, e);
        }
    }

    /** 查用户角色码列表（一人可多角色）。 */
    private List<String> listRoleCodes(Long userId) {
        List<SysUserRole> links = sysUserRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        if (links.isEmpty()) {
            return List.of();
        }
        Set<Long> roleIds = links.stream().map(SysUserRole::getRoleId).collect(Collectors.toSet());
        return sysRoleMapper.selectByIds(roleIds).stream().map(SysRole::getRoleCode).toList();
    }

    /** 多角色取最高等级角色码（与 LoginUser.roleLevel() 同一口径）；无角色返回 null。 */
    private String highestRoleCode(List<String> roleCodes) {
        for (String code : ROLE_PRIORITY) {
            if (roleCodes.contains(code)) {
                return code;
            }
        }
        return null;
    }

    /** 单个用户的管理视角详情（内部复用批量组装，保证与列表出参完全一致）。 */
    private UserDetailVo loadDetail(Long userId) {
        SysUser user = requireUser(userId);
        return composeDetails(List.of(user)).get(0);
    }

    /**
     * 批量组装详情出参：角色与站点名各一次批量查询，避免列表页 N+1。
     */
    private List<UserDetailVo> composeDetails(List<SysUser> users) {
        if (users == null || users.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = users.stream().map(SysUser::getId).toList();

        Map<Long, List<RoleVo>> rolesByUser = new HashMap<>();
        List<SysUserRole> links = sysUserRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getUserId, userIds));
        if (!links.isEmpty()) {
            Set<Long> roleIds = links.stream().map(SysUserRole::getRoleId).collect(Collectors.toSet());
            Map<Long, SysRole> roleById = sysRoleMapper.selectByIds(roleIds).stream()
                    .collect(Collectors.toMap(SysRole::getId, role -> role));
            for (SysUserRole link : links) {
                SysRole role = roleById.get(link.getRoleId());
                if (role != null) {
                    rolesByUser.computeIfAbsent(link.getUserId(), key -> new ArrayList<>()).add(toRoleVo(role));
                }
            }
        }

        Set<Long> stationIds = users.stream().map(SysUser::getStationId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> stationNames = stationIds.isEmpty() ? Map.of()
                : stationMapper.selectByIds(stationIds).stream()
                        .collect(Collectors.toMap(Station::getId, Station::getName));

        List<UserDetailVo> records = new ArrayList<>(users.size());
        for (SysUser user : users) {
            records.add(toDetailVo(user,
                    rolesByUser.getOrDefault(user.getId(), List.of()),
                    user.getStationId() == null ? null : stationNames.get(user.getStationId())));
        }
        return records;
    }

    /** 实体 → 本人信息 VO（手机号脱敏、单值角色取最高等级、出参不携带密码）。 */
    private UserInfoVo toInfoVo(SysUser user, List<String> roleCodes) {
        UserInfoVo vo = new UserInfoVo();
        vo.setId(user.getId());
        vo.setPhone(MaskUtils.maskPhone(user.getPhone()));
        vo.setNickname(user.getNickname());
        vo.setRole(highestRoleCode(roleCodes));
        vo.setStationId(user.getStationId());
        vo.setMustChangePassword(user.getMustChangePassword() != null && user.getMustChangePassword() == 1);
        return vo;
    }

    /** 实体 → 平台管理视角 VO（返回完整登录手机号；角色与站点名由调用方批量装配）。 */
    private UserDetailVo toDetailVo(SysUser user, List<RoleVo> roles, String stationName) {
        UserDetailVo vo = new UserDetailVo();
        vo.setId(user.getId());
        vo.setPhone(user.getPhone());
        vo.setNickname(user.getNickname());
        vo.setStatus(user.getStatus());
        vo.setStationId(user.getStationId());
        vo.setStationName(stationName);
        vo.setRoles(roles);
        vo.setMustChangePassword(user.getMustChangePassword() != null && user.getMustChangePassword() == 1);
        vo.setLastLoginAt(user.getLastLoginAt());
        vo.setCreatedAt(user.getCreatedAt());
        return vo;
    }

    /** 角色实体 → 角色 VO。 */
    private RoleVo toRoleVo(SysRole role) {
        RoleVo vo = new RoleVo();
        vo.setId(role.getId());
        vo.setRoleCode(role.getRoleCode());
        vo.setRoleName(role.getRoleName());
        vo.setDescription(role.getDescription());
        return vo;
    }

    /**
     * 排序白名单（契约 10「orderBy=field,asc|desc，字段白名单」）：只接受已知列，
     * 缺省按 id 倒序（新账号在前）；非法字段抛 3001，绝不把客户端字符串拼进 ORDER BY（9.4）。
     */
    private void applyOrderBy(LambdaQueryWrapper<SysUser> wrapper, String orderBy) {
        if (!StringUtils.hasText(orderBy)) {
            wrapper.orderByDesc(SysUser::getId);
            return;
        }
        String[] parts = orderBy.split(",");
        String field = parts[0].trim();
        boolean asc = parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim());
        switch (field) {
            case "id" -> {
                if (asc) {
                    wrapper.orderByAsc(SysUser::getId);
                } else {
                    wrapper.orderByDesc(SysUser::getId);
                }
            }
            case "createdAt" -> {
                if (asc) {
                    wrapper.orderByAsc(SysUser::getCreatedAt);
                } else {
                    wrapper.orderByDesc(SysUser::getCreatedAt);
                }
            }
            case "lastLoginAt" -> {
                if (asc) {
                    wrapper.orderByAsc(SysUser::getLastLoginAt);
                } else {
                    wrapper.orderByDesc(SysUser::getLastLoginAt);
                }
            }
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "不支持的排序字段：" + field);
        }
    }
}
