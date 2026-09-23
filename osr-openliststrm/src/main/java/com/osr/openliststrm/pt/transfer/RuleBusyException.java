package com.osr.openliststrm.pt.transfer;

/**
 * 同一条转移规则已有线程在执行（定时任务与手动触发撞车）。
 * <p>
 * 单独成类而不是复用 {@link IllegalStateException}：定时任务遇到它只该安静让路，
 * 遇到其它异常才记 warn，两者必须分得开。
 * </p>
 *
 * @author Jack
 */
public class RuleBusyException extends IllegalStateException {

    public RuleBusyException(String ruleName) {
        super("转移规则[" + ruleName + "]正在执行中，请稍后再试");
    }
}
