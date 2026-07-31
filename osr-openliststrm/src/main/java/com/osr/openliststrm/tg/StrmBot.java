package com.osr.openliststrm.tg;

import com.osr.common.utils.ThreadTraceIdUtil;
import com.osr.common.utils.spring.SpringUtils;
import com.osr.openliststrm.orphan.IRenameOrphanScanService;
import com.osr.openliststrm.rename.RenameTaskManager;
import com.osr.openliststrm.service.ICopyService;
import com.osr.openliststrm.service.IStrmService;
import com.osr.openliststrm.task.OpenListStrmTask;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.MDC;
import org.telegram.abilitybots.api.bot.AbilityBot;
import org.telegram.abilitybots.api.objects.Ability;
import org.telegram.abilitybots.api.objects.Flag;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;

import static org.telegram.abilitybots.api.objects.Locality.USER;
import static org.telegram.abilitybots.api.objects.Privacy.CREATOR;
import static org.telegram.abilitybots.api.util.AbilityUtils.getChatId;

/**
 * @Author Jack
 * @Date 2024/6/4 21:33
 * @Version 1.0.0
 */
@Slf4j
public class StrmBot extends AbilityBot {

    private final String adminUserId;

    private final ResponseHandler responseHandler = new ResponseHandler(sender, db);

    public StrmBot(String botToken, String adminUserId) {
        super(botToken, "bot");
        this.adminUserId = adminUserId;
        registerCommands();
    }

    private void registerCommands() {
        List<BotCommand> commands = new ArrayList<>();
        commands.add(new BotCommand("strm", "执行strm任务"));
        commands.add(new BotCommand("strmdir", "生成指定路径strm"));
        commands.add(new BotCommand("sync", "执行copy任务"));
        commands.add(new BotCommand("syncdir", "同步openlist指定目录"));
        commands.add(new BotCommand("retry", "重试所有失败任务"));
        commands.add(new BotCommand("rename", "执行重命名任务"));
        commands.add(new BotCommand("checkorphan", "执行重命名一致性检查"));
        try {
            execute(new SetMyCommands(commands, new BotCommandScopeDefault(), null));
        } catch (TelegramApiException e) {
            log.error("注册tg命令失败", e);
        }
    }

    @Override
    public long creatorId() {
        return Long.parseLong(adminUserId);
    }

    public Ability strm() {
        return Ability.builder()
                .name("strm")
                .info("执行strm任务")
                .privacy(CREATOR)
                .locality(USER)
                .input(0)
                .action(ctx -> {
                    try {
                        ThreadTraceIdUtil.initTraceId();
                        silent.send("==开始执行strm任务==", ctx.chatId());
                        OpenListStrmTask openListStrmTask = SpringUtils.getBean("openListStrmTask");
                        openListStrmTask.strm();
                        silent.send("==执行strm任务完成==", ctx.chatId());
                    } finally {
                        MDC.clear();
                    }
                })
                .build();
    }

    public Ability strmDir() {
        return Ability.builder()
                .name("strmdir")
                .info("生成指定路径strm")
                .privacy(CREATOR)
                .locality(USER)
                .input(0)
                .action(ctx -> {
                    try {
                        ThreadTraceIdUtil.initTraceId();
                        String parameter;
                        try {
                            parameter = ctx.firstArg();
                        } catch (Exception e) {
                            silent.forceReply("请输入路径", ctx.chatId());
                            return;
                        }
                        if (StringUtils.isBlank(parameter)) {
                            silent.forceReply("请输入路径", ctx.chatId());
                            return;
                        }
                        silent.send("==开始执行指定路径strm任务==", ctx.chatId());
                        IStrmService strmService = SpringUtils.getBean(IStrmService.class);
                        strmService.strmDir(parameter);
                        silent.send("==执行指定路径strm任务完成==", ctx.chatId());
                    } finally {
                        MDC.clear();
                    }
                })
                .reply((bot, upd) -> responseHandler.replyToStrmDir(getChatId(upd), upd.getMessage().getText(), upd.getMessage().getMessageId()), Flag.REPLY,//回复
                        upd -> upd.getMessage().getReplyToMessage().hasText(), upd -> upd.getMessage().getReplyToMessage().getText().equals("请输入路径")//回复的是上面的问题
                )
                .build();
    }

    public Ability sync() {
        return Ability.builder()
                .name("sync")
                .info("执行copy任务")
                .privacy(CREATOR)
                .locality(USER)
                .input(0)
                .action(ctx -> {
                    try {
                        ThreadTraceIdUtil.initTraceId();
                        silent.send("==开始执行同步openlist任务==", ctx.chatId());
                        OpenListStrmTask openListStrmTask = SpringUtils.getBean("openListStrmTask");
                        openListStrmTask.copy();
                        silent.send("==执行同步openlist任务完成==", ctx.chatId());
                    } finally {
                        MDC.clear();
                    }
                })
                .build();
    }

    public Ability syncDir() {
        return Ability.builder()
                .name("syncdir")
                .info("同步openlist指定目录")
                .privacy(CREATOR)
                .locality(USER)
                .input(0)
                .action(ctx -> {
                    try {
                        ThreadTraceIdUtil.initTraceId();
                        String parameter;
                        try {
                            parameter = ctx.firstArg();
                        } catch (Exception e) {
                            silent.forceReply("请输入路径(格式：源路径#目标路径)", ctx.chatId());
                            return;
                        }
                        if (StringUtils.isBlank(parameter)) {
                            silent.forceReply("请输入路径(格式：源路径#目标路径)", ctx.chatId());
                            return;
                        }
                        if (!parameter.contains("#")) {
                            silent.send("请输入正确的参数，例如：/阿里云盘/电影#/115网盘/电影", ctx.chatId());
                            return;
                        }
                        String[] strings = parameter.split("#");
                        if (strings.length != 2) {
                            silent.send("请输入正确的参数，例如：/阿里云盘/电影#/115网盘/电影", ctx.chatId());
                            return;
                        }
                        silent.send("==开始执行同步openlist指定目录任务==", ctx.chatId());
                        ICopyService copyService = SpringUtils.getBean(ICopyService.class);
                        copyService.syncFiles(strings[0], strings[1]);
                        silent.send("==执行同步openlist指定目录任务完成==", ctx.chatId());
                    } finally {
                        MDC.clear();
                    }
                })
                .reply((bot, upd) -> responseHandler.replyToSyncDir(getChatId(upd), upd.getMessage().getText(), upd.getMessage().getMessageId()), Flag.REPLY,//回复
                        upd -> upd.getMessage().getReplyToMessage().hasText(), upd -> upd.getMessage().getReplyToMessage().getText().equals("请输入路径(格式：源路径#目标路径)")//回复的是上面的问题
                )
                .build();
    }

    public Ability retry() {
        return Ability.builder()
                .name("retry")
                .info("重试所有失败任务")
                .privacy(CREATOR)
                .locality(USER)
                .input(0)
                .action(ctx -> {
                    try {
                        ThreadTraceIdUtil.initTraceId();
                        silent.send("==开始重试所有失败任务==", ctx.chatId());
                        try {
                            IStrmService strmService = SpringUtils.getBean(IStrmService.class);
                            ICopyService copyService = SpringUtils.getBean(ICopyService.class);
                            RenameTaskManager renameTaskManager = SpringUtils.getBean(RenameTaskManager.class);

                            IStrmService.RetryOutcome strmOutcome = strmService.retryAllFailed();
                            ICopyService.RetryOutcome copyOutcome = copyService.retryAllFailed();
                            RenameTaskManager.RetryOutcome renameOutcome = renameTaskManager.retryAllFailed();

                            int totalRetried = strmOutcome.retried() + copyOutcome.retried() + renameOutcome.retried();
                            if (totalRetried == 0) {
                                silent.send("没有需要重试的失败记录", ctx.chatId());
                            } else {
                                StringBuilder sb = new StringBuilder("==已提交重试请求==\n");
                                sb.append("STRM生成：").append(strmOutcome.retried()).append(" 条");
                                appendRemainingHint(sb, strmOutcome.remaining());
                                sb.append("\n同步：").append(copyOutcome.retried()).append(" 条");
                                appendRemainingHint(sb, copyOutcome.remaining());
                                sb.append("\n重命名：").append(renameOutcome.retried()).append(" 条");
                                appendRemainingHint(sb, renameOutcome.remaining());
                                silent.send(sb.toString(), ctx.chatId());
                            }
                        } catch (Exception e) {
                            log.error("Telegram Bot执行/retry指令异常", e);
                            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                            silent.send("==重试失败==\n" + reason, ctx.chatId());
                        }
                    } finally {
                        MDC.clear();
                    }
                })
                .build();
    }

    private void appendRemainingHint(StringBuilder sb, int remaining) {
        if (remaining > 0) {
            sb.append("（还有 ").append(remaining).append(" 条未提交，可到网页端处理）");
        }
    }

    public Ability rename() {
        return Ability.builder()
                .name("rename")
                .info("执行重命名任务")
                .privacy(CREATOR)
                .locality(USER)
                .input(0)
                .action(ctx -> {
                    try {
                        ThreadTraceIdUtil.initTraceId();
                        silent.send("==开始执行重命名任务==", ctx.chatId());
                        OpenListStrmTask openListStrmTask = SpringUtils.getBean("openListStrmTask");
                        openListStrmTask.rename();
                        silent.send("==执行重命名任务完成==", ctx.chatId());
                    } finally {
                        MDC.clear();
                    }
                })
                .build();
    }

    public Ability checkOrphan() {
        return Ability.builder()
                .name("checkorphan")
                .info("执行重命名一致性检查")
                .privacy(CREATOR)
                .locality(USER)
                .input(0)
                .action(ctx -> {
                    try {
                        ThreadTraceIdUtil.initTraceId();
                        silent.send("==开始执行一致性检查==", ctx.chatId());
                        IRenameOrphanScanService scanService = SpringUtils.getBean(IRenameOrphanScanService.class);
                        IRenameOrphanScanService.ScanSummary summary = scanService.scan();
                        String text = "==一致性检查完成==\n" +
                                "本地文件丢失：" + summary.localMissing() + " 条\n" +
                                "网盘源丢失：" + summary.sourceMissing() + " 条\n" +
                                "已恢复正常：" + summary.resolved() + " 条\n" +
                                "无法解析跳过：" + summary.unparsable() + " 条" +
                                (summary.localMissing() + summary.sourceMissing() > 0
                                        ? "\n（如有待处理项，请到网页端\"重命名一致性检查\"页面确认清理）"
                                        : "");
                        silent.send(text, ctx.chatId());
                    } finally {
                        MDC.clear();
                    }
                })
                .build();
    }

}
