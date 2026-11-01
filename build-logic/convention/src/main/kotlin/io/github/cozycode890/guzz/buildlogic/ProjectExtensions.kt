package io.github.cozycode890.guzz.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/** Version catalog `libs` (gradle/libs.versions.toml) cho convention plugin, vốn không có accessor sinh sẵn. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String =
    findVersion(alias).orElseThrow { IllegalStateException("libs.versions.toml thiếu version '$alias'") }.requiredVersion

internal fun VersionCatalog.versionInt(alias: String): Int = version(alias).toInt()

internal fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalStateException("libs.versions.toml thiếu library '$alias'") }
