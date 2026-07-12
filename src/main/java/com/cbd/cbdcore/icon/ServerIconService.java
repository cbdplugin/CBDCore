package com.cbd.cbdcore.icon;

import org.bukkit.Bukkit;
import org.bukkit.util.CachedServerIcon;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 서버 목록 아이콘 파일 검증·로드와, 로드 실패 시 이전 아이콘 유지 상태를 관리한다.
 */
public final class ServerIconService {

    private final File dataFolder;
    private final Logger logger;
    private final AtomicBoolean applyWarned = new AtomicBoolean(false);

    private volatile CachedServerIcon current;

    public ServerIconService(File dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
    }

    /**
     * plugins/CBDCore 데이터 폴더를 벗어나지 않는 파일 경로만 허용합니다.
     *
     * @throws IllegalArgumentException 경로가 데이터 폴더를 벗어나는 경우
     */
    public File resolveDataFile(String fileName) {
        Path base = dataFolder.toPath().toAbsolutePath().normalize();
        Path target = base.resolve(fileName).normalize();

        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("데이터 폴더를 벗어난 경로입니다: " + fileName);
        }

        return target.toFile();
    }

    /**
     * 지정된 파일에서 아이콘을 다시 로드한다. 경로가 유효하지 않거나, 파일이 없거나,
     * 로드 중 오류가 나면 경고만 남기고 이전 아이콘을 그대로 반환한다.
     */
    public CachedServerIcon loadOrKeepPrevious(String fileName) {
        File iconFile;
        try {
            iconFile = resolveDataFile(fileName);
        } catch (IllegalArgumentException e) {
            logger.warning("잘못된 아이콘 파일 경로입니다: " + fileName);
            return current;
        }

        if (!iconFile.exists()) {
            logger.warning("서버 아이콘 파일을 찾을 수 없습니다: " + iconFile.getPath());
            return current;
        }

        try {
            CachedServerIcon icon = Bukkit.loadServerIcon(iconFile);
            this.current = icon;
            applyWarned.set(false);
            return icon;
        } catch (Exception e) {
            logger.warning("서버 아이콘을 불러오는 중 오류가 발생했습니다 (기존 아이콘 유지): " + e.getMessage());
            return current;
        }
    }

    /**
     * 명령어에서 새 아이콘을 미리 로드/검증한 뒤, 이미 검증된 아이콘을 즉시 반영한다.
     */
    public void applyVerified(CachedServerIcon icon) {
        this.current = icon;
        applyWarned.set(false);
    }

    public CachedServerIcon current() {
        return current;
    }

    /**
     * 서버 핑에 아이콘 적용을 실패했을 때 경고 로그를 한 번만 남기기 위한 헬퍼.
     */
    public boolean shouldWarnApplyFailure() {
        return applyWarned.compareAndSet(false, true);
    }
}
