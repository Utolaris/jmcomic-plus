package com.par9uet.jm.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 解码并发策略测试：
 * - 内存优化开 + 用户并发 1 → 生效 1
 * - 内存优化关 → 恢复硬件默认（普通设备 2），不受隐藏的历史 readDecodeConcurrency=1 影响
 * - low RAM 设备始终不超过 1
 * - 运行时切换内存优化开关立即改变生效值
 */
class ReaderConcurrencyPolicyTest {

    @Test
    fun memoryOptOnFollowsUserConcurrencyClamped() {
        assertEquals(
            1,
            ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                memoryOptEnabled = true,
                userConcurrency = 1,
                lowRamDevice = false,
                memoryClassMb = 512,
            ),
        )
        assertEquals(
            4,
            ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                memoryOptEnabled = true,
                userConcurrency = 4,
                lowRamDevice = false,
                memoryClassMb = 512,
            ),
        )
        // 用户设置 8 → 硬件上限 4
        assertEquals(
            4,
            ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                memoryOptEnabled = true,
                userConcurrency = 8,
                lowRamDevice = false,
                memoryClassMb = 512,
            ),
        )
    }

    @Test
    fun memoryOptOffRestoresNormalDefaultIgnoringHiddenOldValue() {
        // 用户曾设置 1 后关闭内存优化：不得继续被 1 限速。
        assertEquals(
            2,
            ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                memoryOptEnabled = false,
                userConcurrency = 1,
                lowRamDevice = false,
                memoryClassMb = 512,
            ),
        )
        assertEquals(
            2,
            ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                memoryOptEnabled = false,
                userConcurrency = 4,
                lowRamDevice = false,
                memoryClassMb = 512,
            ),
        )
    }

    @Test
    fun lowRamDeviceNeverExceedsHardwareLimit() {
        assertEquals(1, ReaderConcurrencyPolicy.maxDecodeConcurrency(true, 256))
        assertEquals(1, ReaderConcurrencyPolicy.imageWorkConcurrency(true, 256))
        assertEquals(
            1,
            ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                memoryOptEnabled = true,
                userConcurrency = 4,
                lowRamDevice = true,
                memoryClassMb = 256,
            ),
        )
        assertEquals(
            1,
            ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                memoryOptEnabled = false,
                userConcurrency = 4,
                lowRamDevice = true,
                memoryClassMb = 256,
            ),
        )
    }

    @Test
    fun smallMemoryClassClampedLikeLowRam() {
        // memoryClass < 384 与 lowRam 同样视为低内存。
        assertEquals(1, ReaderConcurrencyPolicy.maxDecodeConcurrency(false, 256))
        assertEquals(
            1,
            ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                memoryOptEnabled = false,
                userConcurrency = 1,
                lowRamDevice = false,
                memoryClassMb = 256,
            ),
        )
    }

    @Test
    fun runtimeToggleChangesEffectiveValue() {
        // 模拟运行时开关切换：同一 userConcurrency=1。
        val on = ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
            memoryOptEnabled = true,
            userConcurrency = 1,
            lowRamDevice = false,
            memoryClassMb = 512,
        )
        val off = ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
            memoryOptEnabled = false,
            userConcurrency = 1,
            lowRamDevice = false,
            memoryClassMb = 512,
        )
        assertEquals(1, on)
        assertEquals(2, off)
        // 再次开启恢复用户值。
        val onAgain = ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
            memoryOptEnabled = true,
            userConcurrency = 1,
            lowRamDevice = false,
            memoryClassMb = 512,
        )
        assertEquals(1, onAgain)
    }

    @Test
    fun limiterFollowsRuntimePolicySwitch() {
        val limiter = ReaderDynamicLimiter(
            ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                memoryOptEnabled = true,
                userConcurrency = 1,
                lowRamDevice = false,
                memoryClassMb = 512,
            )
        )
        assertEquals(1, limiter.limit)
        // 关闭内存优化：limiter 立即恢复默认并发。
        limiter.updateLimit(
            ReaderConcurrencyPolicy.effectiveDecodeConcurrency(
                memoryOptEnabled = false,
                userConcurrency = 1,
                lowRamDevice = false,
                memoryClassMb = 512,
            )
        )
        assertEquals(2, limiter.limit)
    }
}
