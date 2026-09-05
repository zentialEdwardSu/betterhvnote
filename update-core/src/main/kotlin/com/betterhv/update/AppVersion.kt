package com.betterhv.update

/** A SemVer-compatible application version. Missing minor/patch parts are treated as zero. */
data class AppVersion(
    val major: Long,
    val minor: Long,
    val patch: Long,
    val preRelease: List<String> = emptyList(),
    val display: String,
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        val coreComparison = sequenceOf(
            compareValues(major, other.major),
            compareValues(minor, other.minor),
            compareValues(patch, other.patch),
        ).firstOrNull { it != 0 } ?: 0
        return coreComparison.takeIf { it != 0 } ?: comparePreRelease(other)
    }

    private fun comparePreRelease(other: AppVersion): Int = when {
        preRelease.isEmpty() && other.preRelease.isNotEmpty() -> 1
        preRelease.isNotEmpty() && other.preRelease.isEmpty() -> -1
        else -> preRelease.zip(other.preRelease)
            .asSequence()
            .map { (left, right) -> compareIdentifier(left, right) }
            .firstOrNull { it != 0 }
            ?: compareValues(preRelease.size, other.preRelease.size)
    }

    companion object {
        private val VERSION = Regex(
            "^(0|[1-9]\\d*)(?:\\.(0|[1-9]\\d*))?(?:\\.(0|[1-9]\\d*))?" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z.-]+)?$"
        )

        fun parse(value: String): AppVersion? {
            val match = VERSION.matchEntire(value.trim()) ?: return null
            return runCatching {
                AppVersion(
                    major = match.groupValues[1].toLong(),
                    minor = match.groupValues[2].ifEmpty { "0" }.toLong(),
                    patch = match.groupValues[3].ifEmpty { "0" }.toLong(),
                    preRelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList(),
                    display = value.trim(),
                )
            }.getOrNull()
        }

        private fun compareIdentifier(left: String, right: String): Int {
            val leftNumber = left.toLongOrNull()
            val rightNumber = right.toLongOrNull()
            return when {
                leftNumber != null && rightNumber != null -> compareValues(leftNumber, rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
        }
    }
}
