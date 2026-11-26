package com.hachicorp.nezh.ui

import androidx.annotation.StringRes
import com.hachicorp.nezh.R

/** Full license texts shipped in `assets/licenses/`, unmodified and in their original language. */
internal enum class LicenseText(val title: String, val asset: String) {
    APACHE_2_0("Apache License, Version 2.0", "licenses/Apache-2.0.txt"),
    MIT_CHECKER_FRAMEWORK("MIT License (Checker Framework qualifiers)", "licenses/MIT-checker-qual.txt"),
    JAVA_ALAC_DECODER("Java Apple Lossless Decoder license", "licenses/BSD-java-alac-decoder.txt"),
    HAMMERTON_ALAC_DECODER("ALAC decoder license (David Hammerton)", "licenses/Hammerton-ALAC-decoder.txt"),
}

/** A third-party component acknowledged on the license notice screen. */
internal data class ThirdPartyComponent(
    val name: String,
    /** Empty when the component has no version of its own (icons). */
    val version: String,
    /** Holder or authors as the component declares them (its sources or its Maven POM); null if it declares none. */
    val holder: String?,
    val licenseName: String,
    val license: LicenseText,
    /** Copyright lines quoted from the component's own files. */
    val copyright: List<String> = emptyList(),
    @StringRes val description: Int? = null,
    val links: List<String> = emptyList(),
    /** Asset listing the libraries the entry groups, one `group:artifact:version` per line. */
    val librariesAsset: String? = null,
)

/**
 * What Nezh ships from third parties: the release runtime classpath (`gradlew :app:dependencies
 * --configuration releaseRuntimeClasspath`; licenses and authors from each POM), the Material
 * icons in res/drawable and the Java Apple Lossless Decoder core included as source
 * (third_party/java-alac-decoder). The library list assets are a snapshot of that classpath:
 * regenerate them when dependencies change.
 */
internal val thirdPartyComponents: List<ThirdPartyComponent> = listOf(
    ThirdPartyComponent(
        name = "Java Apple Lossless Decoder",
        version = "afd5bf3 (2014-04-19)",
        holder = "Peter McQuillan",
        licenseName = "BSD Software License",
        license = LicenseText.JAVA_ALAC_DECODER,
        // license.txt, lines 1 and 3.
        copyright = listOf(
            "Copyright (c) 2011-2014 Peter McQuillan",
            "Based on the ALAC decoder - Copyright (c) 2005 David Hammerton",
        ),
        description = R.string.licenses_java_alac_description,
        links = listOf("https://github.com/soiaf/Java-Apple-Lossless-decoder"),
    ),
    // The original decoder the Java one is ported from: its own notice, shown on its own.
    ThirdPartyComponent(
        name = "ALAC (Apple Lossless Audio Codec) decoder",
        // The version the Java decoder was ported from (its README).
        version = "0.2.0",
        holder = "David Hammerton",
        licenseName = "MIT-style permission notice",
        license = LicenseText.HAMMERTON_ALAC_DECODER,
        copyright = listOf("Copyright (c) 2005 David Hammerton"),
        description = R.string.licenses_hammerton_alac_description,
        links = listOf("http://crazney.net/programs/itunes/alac.html"),
    ),
    ThirdPartyComponent(
        name = "AndroidX Media3",
        version = "1.11.1",
        holder = "The Android Open Source Project",
        licenseName = "Apache License 2.0",
        license = LicenseText.APACHE_2_0,
        description = R.string.licenses_media3_description,
        links = listOf("https://github.com/androidx/media"),
        librariesAsset = "licenses/media3-libraries.txt",
    ),
    ThirdPartyComponent(
        name = "AndroidX (Android Jetpack)",
        version = "",
        holder = "The Android Open Source Project",
        licenseName = "Apache License 2.0",
        license = LicenseText.APACHE_2_0,
        description = R.string.licenses_androidx_description,
        librariesAsset = "licenses/androidx-libraries.txt",
    ),
    ThirdPartyComponent(
        name = "Kotlin Standard Library",
        version = "2.2.10",
        holder = "Kotlin Team",
        licenseName = "Apache License 2.0",
        license = LicenseText.APACHE_2_0,
        links = listOf("https://kotlinlang.org/"),
    ),
    ThirdPartyComponent(
        name = "kotlinx.coroutines",
        version = "1.9.0",
        holder = "JetBrains Team",
        licenseName = "Apache License 2.0",
        license = LicenseText.APACHE_2_0,
        links = listOf("https://github.com/Kotlin/kotlinx.coroutines"),
    ),
    ThirdPartyComponent(
        name = "kotlinx.serialization (core)",
        version = "1.7.3",
        holder = "JetBrains Team",
        licenseName = "Apache License 2.0",
        license = LicenseText.APACHE_2_0,
        links = listOf("https://github.com/Kotlin/kotlinx.serialization"),
    ),
    ThirdPartyComponent(
        name = "Guava: Google Core Libraries for Java (with failureaccess 1.0.2 and listenablefuture)",
        version = "33.3.1-android",
        holder = null,
        licenseName = "Apache License 2.0",
        license = LicenseText.APACHE_2_0,
        description = R.string.licenses_guava_description,
        links = listOf("https://github.com/google/guava"),
    ),
    ThirdPartyComponent(
        name = "JetBrains Java Annotations",
        version = "23.0.0",
        holder = "JetBrains Team",
        licenseName = "Apache License 2.0",
        license = LicenseText.APACHE_2_0,
        description = R.string.licenses_annotations_description,
        links = listOf("https://github.com/JetBrains/java-annotations"),
    ),
    ThirdPartyComponent(
        name = "Error Prone annotations",
        version = "2.28.0",
        holder = null,
        licenseName = "Apache License 2.0",
        license = LicenseText.APACHE_2_0,
        description = R.string.licenses_annotations_description,
    ),
    ThirdPartyComponent(
        name = "J2ObjC annotations",
        version = "3.0.0",
        holder = "Tom Ball",
        licenseName = "Apache License 2.0",
        license = LicenseText.APACHE_2_0,
        description = R.string.licenses_annotations_description,
        links = listOf("https://github.com/google/j2objc/"),
    ),
    ThirdPartyComponent(
        name = "JSR 305 annotations (FindBugs)",
        version = "3.0.2",
        holder = null,
        licenseName = "Apache License 2.0",
        license = LicenseText.APACHE_2_0,
        description = R.string.licenses_annotations_description,
        links = listOf("http://findbugs.sourceforge.net/"),
    ),
    ThirdPartyComponent(
        name = "JSpecify",
        version = "1.0.0",
        holder = "Kevin Bourrillion",
        licenseName = "Apache License 2.0",
        license = LicenseText.APACHE_2_0,
        description = R.string.licenses_annotations_description,
        links = listOf("http://jspecify.org/"),
    ),
    ThirdPartyComponent(
        name = "Checker Framework qualifiers",
        version = "3.43.0",
        holder = "The Checker Framework developers",
        licenseName = "MIT",
        license = LicenseText.MIT_CHECKER_FRAMEWORK,
        copyright = listOf("Copyright 2004-present by the Checker Framework developers"),
        description = R.string.licenses_annotations_description,
        links = listOf("https://checkerframework.org/"),
    ),
    ThirdPartyComponent(
        name = "Material Design icons",
        version = "",
        holder = null,
        licenseName = "Apache License 2.0",
        license = LicenseText.APACHE_2_0,
        description = R.string.licenses_material_icons_description,
    ),
)
