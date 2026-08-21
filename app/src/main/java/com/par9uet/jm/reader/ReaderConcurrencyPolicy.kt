package com.par9uet.jm.reader

/**
 * 阅读解码并发策略（纯函数，便于单元测试）。
 *
 * 语义：
 * - readMemoryOptEnabled = true：跟随用户设置的 readDecodeConcurrency，但仍受硬件
 *   安全上限 maxDecodeConcurrency 约束。
 * - readMemoryOptEnabled = false：恢复硬件安全默认并发（普通设备 2，低内存设备 1），
 *   不再读取历史保存的 readDecodeConcurrency，避免 UI 已隐藏的旧值（如 1）意外限速。
 */
internal object ReaderConcurrencyPolicy {
    /** 图片工作默认并发：低内存设备始终为 1。 */
    fun imageWorkConcurrency(lowRamDevice: Boolean, memoryClassMb: Int): Int =
        if (lowRamDevice || memoryClassMb < 384) 1 else 2

    /** 用户可配置的解码并发上限（硬件安全限制）。 */
    fun maxDecodeConcurrency(lowRamDevice: Boolean, memoryClassMb: Int): Int =
        if (imageWorkConcurrency(lowRamDevice, memoryClassMb) == 1) 1 else 4

    /**
     * 生效的解码并发。
     * @param userConcurrency 用户保存的 readDecodeConcurrency（仅内存优化开启时参与）。
     */
    fun effectiveDecodeConcurrency(
        memoryOptEnabled: Boolean,
        userConcurrency: Int,
        lowRamDevice: Boolean,
        memoryClassMb: Int,
    ): Int {
        val max = maxDecodeConcurrency(lowRamDevice, memoryClassMb)
        val normalDefault = imageWorkConcurrency(lowRamDevice, memoryClassMb)
        return if (memoryOptEnabled) {
            userConcurrency.coerceIn(1, max)
        } else {
            normalDefault.coerceIn(1, max)
        }
    }
}
