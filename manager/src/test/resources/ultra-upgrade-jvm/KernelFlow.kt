package dev.maplesu.manager.ultrakernel

import android.content.Context
import dev.maplesu.manager.runtime.ultra.Fixture
import java.io.File

object UltraKernelFlow {
    fun initBootArtifact(context: Context, pairedBoot: File): dev.maplesu.image.UltraInitBootProducer.Artifact? {
        Fixture.calls += "component"; return Fixture.component
    }
}
