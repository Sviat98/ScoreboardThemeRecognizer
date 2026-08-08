package com.bashkevich.scoreboardthemerecognizer.model.file.domain

data class ImageFile(
    val name: String,
    val content: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ImageFile

        if (name != other.name) return false
        if (!content.contentEquals(other.content)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}

val EMPTY_IMAGE_FILE = ImageFile(name = "", content = ByteArray(0))
