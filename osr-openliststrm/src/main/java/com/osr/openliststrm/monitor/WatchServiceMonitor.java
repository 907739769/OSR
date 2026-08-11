package com.osr.openliststrm.monitor;

import com.osr.common.utils.StringUtils;
import com.osr.common.utils.Threads;
import com.osr.openliststrm.helper.TgHelper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * 基于WatchService的文件实时监控实现
 *
 * @author: Jack
 * @creat: 2026/1/12 14:40
 */
@Slf4j
public class WatchServiceMonitor implements FileMonitor {

    private final Path root;

    /**
     * 目录名过滤器：返回 true 的目录整棵跳过——既不注册 WatchService，也不补扫里面的文件。
     * <p>
     * 存在的理由是 Transmission 删种：它会先建一个 {@code <种子名>__<mkdtemp 6位随机>} 的临时目录、
     * 把内容整个挪进去再删（见 {@code OpenListHelper#isTransientDir}）。监控目录如果就是下载目录，
     * 这个动作在 WatchService 眼里是"凭空出现一个装满视频文件的新目录"，
     * {@link #handleCreate} 会老老实实递归注册它、并给里面每个文件发一条 CREATE 事件，
     * 下游处理器（重命名/上传）随即对着一批正在被删除的文件开工。
     * <p>
     * 判据只能是名字，所以过滤放在这里而不是下游：在<b>注册之前</b>拦住，比事后逐个文件判便宜得多，
     * 也不会给一棵马上要消失的目录树留下 WatchKey。
     */
    private final Predicate<String> skipDir;

    private WatchService watchService;
    private Consumer<FileEvent> listener;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public WatchServiceMonitor(Path root) {
        this(root, name -> false);
    }

    public WatchServiceMonitor(Path root, Predicate<String> skipDir) {
        this.root = root;
        this.skipDir = skipDir == null ? name -> false : skipDir;
    }

    @Override
    public void setListener(Consumer<FileEvent> listener) {
        this.listener = listener;
    }

    @Override
    public void start() {
        executor.submit(Threads.wrap(this::run));
    }

    private void run() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            registerAll(root);

            while (true) {
                WatchKey key = watchService.take();
                Path dir = (Path) key.watchable();

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path p = (Path) event.context();
                    Path fullPath = dir.resolve(p).toAbsolutePath().normalize();
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == ENTRY_CREATE) {
                        handleCreate(fullPath);
                    } else if (kind == ENTRY_MODIFY) {
                        handleModify(fullPath);
                    } else if (kind == ENTRY_DELETE) {
                        handleDelete(fullPath);
                    } else if (kind == OVERFLOW) {
                        log.warn("文件系统事件溢出，可能丢失了部分文件变更: {}！建议手动触发全量扫描。", root.toString());
                        TgHelper.sendMsg("<b>监控任务丢失文件事件</b>\n" +
                                "文件系统事件溢出，可能丢失了部分文件变更: " + StringUtils.escapeHtml(root.toString()) + "！建议手动触发全量扫描。");
                    }
                }
                key.reset();
            }
        } catch (ClosedWatchServiceException e) {
            log.info("WatchService closed, monitor exiting");
        } catch (Exception e) {
            log.error("watch loop error", e);
        }
    }

    @Override
    public void stop() {
        try {
            if (watchService != null) watchService.close();
        } catch (IOException ignored) {
        }
        Threads.shutdownAndAwaitTermination(executor);
    }

    private void handleCreate(Path path) {
        try {
            if (Files.isDirectory(path)) {
                if (shouldSkip(path)) {
                    // 内容正在被下载器删除，注册它只会换来一棵马上失效的 WatchKey 和一批白跑的处理任务
                    log.info("跳过疑似删种临时目录，不注册也不补扫: {}", path);
                    return;
                }
                // 新目录：必须递归注册
                registerAll(path);

                // 补扫目录内已有文件（极关键）
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        // 嵌套的临时目录同样整棵跳过，与 registerAll 的口径保持一致
                        return skipSubtree(dir, path) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (listener != null && Files.isRegularFile(file)) {
                            listener.accept(new FileEvent(file, FileEvent.Type.CREATE));
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } else {
                if (listener != null) {
                    listener.accept(new FileEvent(path, FileEvent.Type.CREATE));
                }
            }
        } catch (Exception e) {
            log.error("handleCreate error {}", path, e);
        }
    }

    private void handleModify(Path path) {
        if (!Files.isDirectory(path)) {
            listener.accept(new FileEvent(path, FileEvent.Type.MODIFY));
        }
    }

    private void handleDelete(Path path) {
    }


    /**
     * 递归注册。命中 {@link #skipDir} 的子目录整棵跳过，边界见 {@link #skipSubtree}。
     */
    private void registerAll(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (skipSubtree(dir, start)) {
                    log.info("跳过疑似删种临时目录，不注册监控: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 目录名是否命中跳过规则。取文件名部分判定，根目录（getFileName 为 null）一律不跳 */
    private boolean shouldSkip(Path dir) {
        Path name = dir.getFileName();
        return name != null && skipDir.test(name.toString());
    }

    /**
     * 遍历时该不该整棵跳过 {@code dir}。
     * <p>
     * <b>{@code start} 自己永远不跳</b>：监控根/新建根目录要是碰巧长得像临时目录（用户就这么命名的），
     * 跳过它等于把这次注册或补扫静默取消掉，症状是"监控明明起来了却一个文件都不处理"，
     * 比多注册一棵目录难查得多。包级可见是为了让这条边界能被单测直接钉住。
     */
    boolean skipSubtree(Path dir, Path start) {
        return !dir.equals(start) && shouldSkip(dir);
    }

    private void register(Path dir) throws IOException {
        Path p = dir.toAbsolutePath().normalize();
        p.register(watchService, ENTRY_CREATE, ENTRY_MODIFY);
        log.debug("Registered {}", p);
    }

}
