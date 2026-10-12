package com.semavoice.core.models

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

data class DeviceProfile(
    val totalRamMb: Int,
    val availableRamMb: Int,
    val cores: Int,
    val abi: String,
    val hasArmV82: Boolean,
    val sdk: Int,
    val tier: PerformanceTier,
) {
    val isSupportedAbi: Boolean get() = abi == "arm64-v8a" || abi == "x86_64"
}

/** Pure decision logic, unit-tested separately from the Android probes. */
object CompatibilityRules {
    fun tierFor(totalRamMb: Int, cores: Int): PerformanceTier = when {
        totalRamMb >= 6_000 && cores >= 8 -> PerformanceTier.HIGH
        totalRamMb >= 3_500 && cores >= 6 -> PerformanceTier.MID
        else -> PerformanceTier.LOW
    }

    sealed class Verdict {
        data object Ok : Verdict()
        data class TooLittleRam(val requiredMb: Int, val availableMb: Int) : Verdict()
        data class SlowTier(val needed: PerformanceTier, val have: PerformanceTier) : Verdict()
        data class UnsupportedAbi(val abi: String) : Verdict()
        data object MissingCpuFeatures : Verdict()
    }

    fun check(model: ModelInfo, device: DeviceProfile): Verdict {
        if (!device.isSupportedAbi) return Verdict.UnsupportedAbi(device.abi)
        if (device.abi == "arm64-v8a" && !device.hasArmV82) return Verdict.MissingCpuFeatures
        // Keep ~35% of RAM free for the OS and the host app the keyboard is attached to.
        val budget = (device.totalRamMb * 0.65).toInt()
        if (model.requiredRamMb > budget) return Verdict.TooLittleRam(model.requiredRamMb, budget)
        if (model.minTier.ordinal > device.tier.ordinal) return Verdict.SlowTier(model.minTier, device.tier)
        return Verdict.Ok
    }

    fun canRun(model: ModelInfo, device: DeviceProfile): Boolean =
        check(model, device).let { it is Verdict.Ok || it is Verdict.SlowTier }
}

class DeviceCompatibility(private val context: Context) {
    fun profile(): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val total = (mi.totalMem / (1024 * 1024)).toInt()
        val avail = (mi.availMem / (1024 * 1024)).toInt()
        val cores = Runtime.getRuntime().availableProcessors()
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        return DeviceProfile(
            totalRamMb = total, availableRamMb = avail, cores = cores, abi = abi,
            hasArmV82 = hasArmV82Features(abi), sdk = Build.VERSION.SDK_INT,
            tier = CompatibilityRules.tierFor(total, cores),
        )
    }

    private fun hasArmV82Features(abi: String): Boolean {
        if (abi != "arm64-v8a") return abi == "x86_64"
        val cpuinfo = runCatching { File("/proc/cpuinfo").readText() }.getOrNull() ?: return true
        val line = cpuinfo.lineSequence().firstOrNull { it.startsWith("Features") } ?: return true
        return line.contains("fphp") && line.contains("asimddp")
    }

    fun availableStorageBytes(dir: File): Long = StatFs(dir.absolutePath).availableBytes
}
