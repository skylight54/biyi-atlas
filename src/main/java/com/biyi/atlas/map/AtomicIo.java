package com.biyi.atlas.map;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 原子写盘与迁移备份。
 *
 * <p>范式直接照 FTB Library 的 {@code SNBT.write}：先写**同目录**的 {@code <file>.tmp}，
 * 再 {@code Files.move(..., ATOMIC_MOVE)} 原子替换；不支持原子移动时降级为普通替换。
 * 这样"写到一半崩溃"永远不会留下半截文件。</p>
 */
public final class AtomicIo {

    private AtomicIo() {
    }

    /** 原子写入。 */
    public static void writeAtomic(Path path, byte[] data) throws IOException {
        Path parent = path.getParent();
        if (parent != null && Files.notExists(parent)) {
            Files.createDirectories(parent);
        }
        Path tmp = parent == null
                ? Path.of(path.getFileName().toString() + ".tmp")
                : parent.resolve(path.getFileName().toString() + ".tmp");
        Files.write(tmp, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 迁移前备份：{@code <file>.bak.v<旧版本号>}（比裸 {@code .bak} 更能定点回滚）。
     * 文件不存在时静默跳过。
     */
    public static Path backup(Path path, int oldVersion) throws IOException {
        if (Files.notExists(path)) {
            return null;
        }
        Path target = path.resolveSibling(path.getFileName().toString() + ".bak.v" + oldVersion);
        Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
