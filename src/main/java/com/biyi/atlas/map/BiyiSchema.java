package com.biyi.atlas.map;

/**
 * 存档版本号（三层，照 Vanilla/DFU 的分层惯例）。
 *
 * <ul>
 *   <li>{@link #FILE_VERSION} —— 文件**信封**版本：根结构/键名/整体布局变化才 +1</li>
 *   <li>{@link #REGIONS_SCHEMA} —— 区域套（**被引用方**，迁移时先迁）</li>
 *   <li>{@link #ROADS_SCHEMA} —— 路网套（**引用方**，迁移时后迁）</li>
 * </ul>
 *
 * <p>DFU 的 key 编码是 {@code version*10 + subVersion}，因此 subVersion 只能是 0..9。</p>
 */
public final class BiyiSchema {

    public static final int FILE_VERSION = 1;
    public static final int REGIONS_SCHEMA = 2;   // v2：子区域默认"锁定即隐藏其内容"（分层锁）
    public static final int ROADS_SCHEMA = 1;

    private BiyiSchema() {
    }

    public static int key(int version, int subVersion) {
        return version * 10 + subVersion;
    }

    public static int version(int key) {
        return key / 10;
    }

    public static int subVersion(int key) {
        return key % 10;
    }

    /** 日志/界面里显示用，例如 {@code V1} 或 {@code V1.1}。 */
    public static String display(int version, int subVersion) {
        return "V" + version + (subVersion == 0 ? "" : "." + subVersion);
    }

    /**
     * 两套 schema 是否处于"迁移中断"状态：既不是"都是当前版本"，也不是"两套一致"。
     *
     * <p>迁移是**单向、逐级、同事务**的：先迁 regions 再迁 roads，两者都到当前才算完成。
     * 因此"两套号不一致"是迁移被打断的显式信号，必须拒绝写回。</p>
     */
    public static boolean interrupted(int regionsSchema, int roadsSchema) {
        if (regionsSchema == REGIONS_SCHEMA && roadsSchema == ROADS_SCHEMA) {
            return false;
        }
        return regionsSchema != roadsSchema;
    }

    /** 是否有任何一层比当前程序更新（→ 只能只读）。 */
    public static boolean tooNew(int fileVersion, int regionsSchema, int roadsSchema) {
        return fileVersion > FILE_VERSION || regionsSchema > REGIONS_SCHEMA || roadsSchema > ROADS_SCHEMA;
    }
}
