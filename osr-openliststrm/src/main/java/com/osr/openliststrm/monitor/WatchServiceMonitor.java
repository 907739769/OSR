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
    private WatchService watchService;
    private Consumer<FileEvent> listener;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public WatchServiceMonitor(Path root) {
        this.root = root;
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
                // 新目录：必须递归注册
                registerAll(path);

                // 补扫目录内已有文件（极关键）
                Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
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
     * 递归注册
     */
    private void registerAll(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                register(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void register(Path dir) throws IOException {
        Path p = dir.toAbsolutePath().normalize();
        p.register(watchService, ENTRY_CREATE, ENTRY_MODIFY);
        log.debug("Registered {}", p);
    }

}
