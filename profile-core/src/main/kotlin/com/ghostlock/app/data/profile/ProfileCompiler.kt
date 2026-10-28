package com.ghostlock.app.data.profile

import com.ghostlock.app.data.HoconSupport
import com.ghostlock.app.data.NativeProfileDocument
import com.ghostlock.app.data.ValueMap
import com.ghostlock.app.data.asValueMap
import com.ghostlock.app.data.route.RouteKind
import java.io.File

/**
 * Runtime counterpart of the upstream `ProfileExporter`: compiles one HOCON
 * kernel profile into the GLK1 binary the native core reads.
 *
 * The caller downloads the profile and its `include`d files (plus the
 * `execution-*.conf` presets) into one directory; includes are expanded here
 * relative to that directory, exactly like the exporter does for assets.
 */
object ProfileCompiler {
    /** @throws IllegalStateException when the profile is missing, invalid or unsupported. */
    @JvmStatic
    fun compile(profileFile: File): ByteArray {
        val dir = profileFile.parentFile ?: error("profile has no parent directory")
        val parsed = parseFile(profileFile, dir) ?: error("cannot parse ${profileFile.name}")
        val release = parsed["release"] as? String ?: error("${profileFile.name} has no release")

        val routes = RouteKind.entries.map { it.token }
        val tuning = parseFile(File(dir, "execution-tuning.conf"), dir)?.get("execution").asValueMap()
        val presets = routes.mapNotNull { route ->
            parseFile(File(dir, "execution-${route.replace('_', '-')}.conf"), dir)
                ?.get("execution").asValueMap()
                ?.get("routes").asValueMap()
                ?.get(route).asValueMap()
                ?.let { route to it }
        }.toMap()

        val route = routeNameOf(parsed, routes)
        val fallbackTo = fallbackOf(parsed)
        val merged = ProfileMerger.resolveMerged(
            deviceRelease = release,
            builtin = parsed,
            imported = null,
            overrides = null,
            tuningExecution = tuning,
            pair = CpuPairView(0, 1),
            routePresets = presets,
        )
        val errors = ProfileResolver.validateMerged(merged, route, fallbackTo)
        if (errors.isNotEmpty()) {
            error("invalid profile: " + errors.joinToString { it.fieldPath })
        }
        return NativeProfileDocument.from(
            release = release,
            route = RouteKind.fromToken(route)?.token,
            fallbackTo = RouteKind.fromToken(fallbackTo)?.token,
        ) { path -> ProfileResolver.nativeValue(merged, route, fallbackTo, path) }.toBinary()
    }

    private fun routeNameOf(profile: Map<*, *>, routes: List<String>): String? =
        when (val route = profile["route"]) {
            is String -> route.takeIf { it.isNotEmpty() && it != "null" }
            is Map<*, *> -> route.keys.filterIsInstance<String>().firstOrNull { it in routes }
            else -> null
        }

    private fun fallbackOf(profile: Map<*, *>): String? {
        val to = ((profile["fallback"] as? Map<*, *>)?.get("to") as? String)
            ?.takeIf { it.isNotEmpty() && it != "null" }
        if (to != null) return to
        return (profile["fallback_to"] as? String)?.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun parseFile(file: File, baseDir: File): ValueMap? {
        if (!file.isFile) return null
        return runCatching { HoconSupport.parseValue(expand(file, baseDir, linkedSetOf())).asValueMap() }
            .getOrNull()
    }

    private fun expand(file: File, baseDir: File, visiting: MutableSet<String>): String {
        val name = file.absolutePath
        if (!visiting.add(name)) return ""
        val text = runCatching { file.readText() }.getOrElse {
            visiting.remove(name)
            return ""
        }
        val builder = StringBuilder()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            val target = when {
                trimmed.startsWith("#") || trimmed.startsWith("//") -> null
                trimmed.startsWith("include ") ->
                    trimmed.removePrefix("include ").trim().trim('"').takeIf { it.isNotEmpty() }

                else -> null
            }
            if (target == null) {
                builder.append(line).append('\n')
            } else {
                builder.append(expand(File(baseDir, target), baseDir, visiting)).append('\n')
            }
        }
        visiting.remove(name)
        return builder.toString()
    }
}
