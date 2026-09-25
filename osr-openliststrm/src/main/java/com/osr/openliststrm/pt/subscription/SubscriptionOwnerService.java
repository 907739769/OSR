package com.osr.openliststrm.pt.subscription;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.osr.common.core.domain.entity.SysUser;
import com.osr.common.utils.StringUtils;
import com.osr.openliststrm.mybatisplus.domain.PtSubscriptionPlus;
import com.osr.openliststrm.mybatisplus.service.IPtSubscriptionPlusService;
import com.osr.system.service.ISysUserService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 多用户下「这条订阅是谁的」：订阅页与追剧日历的「归属」筛选、卡片上的归属人名字。
 * <p>
 * 筛选只在<b>可见范围之内</b>再收窄，从不放宽：可见性（管理员看全部，其余人看自己的与无归属的）
 * 仍由各 Controller 的 canAccess 口径决定，这里多传一个别人的 userId 也只会得到空集。
 * </p>
 * <p>
 * 筛选值：{@value #MINE}（我的）/ {@value #PUBLIC}（无归属的公共订阅，本列上线前建的历史订阅）/ 某个用户 id；
 * 空或无法识别 = 不筛。
 * </p>
 *
 * @author Jack
 */
@Service
public class SubscriptionOwnerService {

    public static final String MINE = "mine";
    public static final String PUBLIC = "public";

    private final IPtSubscriptionPlusService subscriptionService;
    private final ISysUserService userService;

    public SubscriptionOwnerService(IPtSubscriptionPlusService subscriptionService, ISysUserService userService) {
        this.subscriptionService = subscriptionService;
        this.userService = userService;
    }

    /** 归属筛选的一个选项；count 是当前用户可见范围内的订阅数 */
    public record OwnerOption(String value, String label, long count) {
    }

    /** 把筛选条件加到列表查询上 */
    public static void apply(LambdaQueryWrapper<PtSubscriptionPlus> wrapper, String filter, Long me) {
        if (StringUtils.isBlank(filter)) {
            return;
        }
        if (MINE.equals(filter)) {
            if (me == null) {
                // 取不到当前用户时「我的」必然为空。显式写成恒假，不交给 eq(null)——那会生成 `= NULL`，
                // 结果碰巧也是空，但读代码的人看不出这是有意的
                wrapper.apply("1 = 0");
            } else {
                wrapper.eq(PtSubscriptionPlus::getOwnerUserId, me);
            }
        } else if (PUBLIC.equals(filter)) {
            wrapper.isNull(PtSubscriptionPlus::getOwnerUserId);
        } else if (filter.matches("\\d{1,19}")) {
            wrapper.eq(PtSubscriptionPlus::getOwnerUserId, Long.valueOf(filter));
        }
    }

    /** 与 {@link #apply} 同一口径的内存判定，供日历这类先查集再按订阅过滤的地方用 */
    public static boolean matches(PtSubscriptionPlus sub, String filter, Long me) {
        if (StringUtils.isBlank(filter)) {
            return true;
        }
        if (MINE.equals(filter)) {
            return me != null && me.equals(sub.getOwnerUserId());
        }
        if (PUBLIC.equals(filter)) {
            return sub.getOwnerUserId() == null;
        }
        if (filter.matches("\\d{1,19}")) {
            return Long.valueOf(filter).equals(sub.getOwnerUserId());
        }
        return true;
    }

    /**
     * 当前用户可选的归属选项：我的、公共，管理员另有其余每个有订阅的用户。数量为 0 的「我的」「公共」也给出，
     * 其余用户只列有订阅的。
     */
    public List<OwnerOption> options(boolean admin, Long me) {
        QueryWrapper<PtSubscriptionPlus> query = new QueryWrapper<PtSubscriptionPlus>()
                .select("owner_user_id", "count(*) as cnt").groupBy("owner_user_id");
        if (!admin) {
            if (me == null) {
                query.isNull("owner_user_id");
            } else {
                query.and(w -> w.eq("owner_user_id", me).or().isNull("owner_user_id"));
            }
        }
        Map<Long, Long> counts = new HashMap<>();
        long publicCount = 0;
        for (Map<String, Object> row : subscriptionService.listMaps(query)) {
            if (row == null) {
                continue;
            }
            long cnt = row.get("cnt") == null ? 0 : Long.parseLong(row.get("cnt").toString());
            Object owner = row.get("owner_user_id");
            if (owner == null) {
                publicCount = cnt;
            } else {
                counts.put(Long.valueOf(owner.toString()), cnt);
            }
        }
        List<OwnerOption> options = new ArrayList<>();
        options.add(new OwnerOption(MINE, "我的", me == null ? 0 : counts.getOrDefault(me, 0L)));
        options.add(new OwnerOption(PUBLIC, "公共（无归属）", publicCount));
        counts.forEach((userId, cnt) -> {
            if (!userId.equals(me)) {
                options.add(new OwnerOption(String.valueOf(userId), nameOf(userId), cnt));
            }
        });
        options.subList(2, options.size()).sort((a, b) -> a.label().compareTo(b.label()));
        return options;
    }

    /**
     * 给列表里<b>别人的</b>订阅填上归属人名字（管理员视图用），同一页里同一个人只查一次。
     * 自己的与公共订阅不填：卡片上满屏写着自己的名字只是噪音。
     */
    public void fillOwnerNames(List<PtSubscriptionPlus> subs, Long me) {
        Map<Long, String> names = new HashMap<>();
        subs.stream().map(PtSubscriptionPlus::getOwnerUserId).filter(Objects::nonNull).filter(id -> !id.equals(me))
                .distinct().forEach(id -> names.put(id, nameOf(id)));
        subs.forEach(s -> s.setOwnerName(names.get(s.getOwnerUserId())));
    }

    /** 用户名称（RuoYi 的 userName 是昵称）优先，其次登录名；用户已被删除时写明，而不是留空让人以为是公共订阅 */
    private String nameOf(Long userId) {
        SysUser user = userService.selectUserById(userId);
        if (user == null) {
            return "已删除用户#" + userId;
        }
        return StringUtils.isNotBlank(user.getUserName()) ? user.getUserName() : user.getLoginName();
    }
}
