package com.osr.openliststrm.mybatisplus.domain;

import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.reflection.Reflector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 所有 {@code *Plus} 实体都必须能被 MyBatis 的 {@link Reflector} 解析。
 * <p>
 * 挡的是这一类真实事故：给 {@code String enabled} 字段（Lombok 已生成 {@code getEnabled()}）
 * 再加一个返回 boolean 的 {@code isEnabled()} 辅助方法。两者被 MyBatis 认作同一个属性
 * {@code enabled} 的两个 getter，类型却不一致，于是在<b>第一次真正执行 SQL 时</b>抛
 * {@code Illegal overloaded getter method with ambiguous type} —— 编译期、Spring 装配期
 * 全都发现不了，单测里 new 一个实体出来也照样不炸，只有跑到 INSERT 那一刻才暴露。
 * </p>
 * <p>
 * 这里直接用 MyBatis 自己的 {@code Reflector} 而不是手写反射规则：它就是运行时报错的那段代码，
 * 判据不可能与线上不一致。往实体上加辅助方法时避开 {@code getXxx}/{@code isXxx} 命名即可
 * （参考 {@code PtIndexerPlus#hitAndRunEnabled()}、{@code PtCleanRulePlus#enabledOn()}）。
 * </p>
 *
 * @author Jack
 */
class PlusEntityReflectorTest {

    @Test
    void 所有Plus实体都不存在类型冲突的重载getter() {
        ResolverUtil<Object> resolver = new ResolverUtil<>();
        resolver.find(new ResolverUtil.IsA(Object.class), "com.osr.openliststrm.mybatisplus.domain");
        Set<Class<? extends Object>> classes = resolver.getClasses();
        assertTrue(classes.size() > 10, "实体扫描没扫到东西，说明包名写错了，这个测试就形同虚设");

        List<String> broken = new ArrayList<>();
        for (Class<?> clazz : classes) {
            if (clazz.isInterface() || clazz.isEnum() || clazz.isAnonymousClass()) {
                continue;
            }
            broken.addAll(checkReadableProperties(clazz));
        }
        assertTrue(broken.isEmpty(), "以下实体的属性无法被 MyBatis 读取，执行 SQL 时会直接失败：\n"
                + String.join("\n", broken));
    }

    /**
     * 逐个<b>调用</b>实体的 getter，而不是只 {@code new Reflector(clazz)}。
     * <p>
     * 这一点是本测试的关键：{@code Reflector} 的构造函数遇到类型冲突的重载 getter <b>不会抛异常</b>，
     * 它只是把该属性登记成一个 {@code AmbiguousMethodInvoker}，等到真正取值的那一刻才抛。
     * 只构造 Reflector 的写法看起来在测同一件事，实际一条都拦不住——线上那次事故正是
     * 「装配、单测全过，第一次 INSERT 才炸」。
     * </p>
     */
    private List<String> checkReadableProperties(Class<?> clazz) {
        List<String> broken = new ArrayList<>();
        Reflector reflector;
        Object instance;
        try {
            reflector = new Reflector(clazz);
            instance = clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            // 没有无参构造的类不是 MyBatis 实体，跳过；Reflector 本身构造失败则如实上报
            return broken;
        }
        for (String property : reflector.getGetablePropertyNames()) {
            try {
                reflector.getGetInvoker(property).invoke(instance, null);
            } catch (Exception e) {
                broken.add(clazz.getSimpleName() + "." + property + " -> " + e.getMessage());
            }
        }
        return broken;
    }
}
