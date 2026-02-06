package app.morphe.gui.data.model

/**
 * Represents a supported app extracted dynamically from patch metadata.
 * This is populated by parsing the .mpp file's compatible packages.
 */
data class SupportedApp(
    val packageName: String,
    val displayName: String,
    val supportedVersions: List<String>,
    val recommendedVersion: String?,
    val apkMirrorUrl: String? = null
) {
    companion object {
        /**
         * Derive display name from package name.
         */
        fun getDisplayName(packageName: String): String {
            return when (packageName) {
                "com.google.android.youtube" -> "YouTube"
                "com.google.android.apps.youtube.music" -> "YouTube Music"
                "com.reddit.frontpage" -> "Reddit"
                else -> {
                    // Fallback: Extract last part of package name and capitalize
                    packageName.substringAfterLast(".")
                        .replaceFirstChar { it.uppercase() }
                }
            }
        }

        /**
         * Get APK Mirror URL for a package name.
         */
        fun getApkMirrorUrl(packageName: String): String? {
            return when (packageName) {
                "com.google.android.youtube" -> "https://www.apkmirror.com/apk/google-inc/youtube/"
                "com.google.android.apps.youtube.music" -> "https://www.apkmirror.com/apk/google-inc/youtube-music/"
                "com.reddit.frontpage" -> "https://www.apkmirror.com/apk/redditinc/reddit/"
                else -> null
            }
        }

        /**
         * Get the recommended version from a list of supported versions.
         * Returns the highest version number.
         */
        fun getRecommendedVersion(versions: List<String>): String? {
            if (versions.isEmpty()) return null

            return versions.sortedWith { v1, v2 ->
                compareVersions(v2, v1) // Descending order
            }.firstOrNull()
        }

        /**
         * Compare two version strings.
         * Returns positive if v1 > v2, negative if v1 < v2, 0 if equal.
         */
        private fun compareVersions(v1: String, v2: String): Int {
            val parts1 = v1.split(".").mapNotNull { it.toIntOrNull() }
            val parts2 = v2.split(".").mapNotNull { it.toIntOrNull() }

            for (i in 0 until maxOf(parts1.size, parts2.size)) {
                val p1 = parts1.getOrElse(i) { 0 }
                val p2 = parts2.getOrElse(i) { 0 }
                if (p1 != p2) return p1.compareTo(p2)
            }
            return 0
        }
    }
}
