package eu.kanade.tachiyomi.source.model

import java.io.Serializable

interface SManga : Serializable {

    var url: String

    var title: String

    var artist: String?

    var author: String?

    var description: String?

    var genre: String?

    var status: Int

    var thumbnail_url: String?

    var update_strategy: UpdateStrategy

    var initialized: Boolean

    fun getGenres(): List<String>? {
        if (genre.isNullOrBlank()) return null
        return genre?.split(", ")?.map { it.trim() }?.filterNot { it.isBlank() }?.distinct()
    }

    // SY -->
    val originalTitle: String
    val originalAuthor: String?
    val originalArtist: String?
    val originalDescription: String?
    val originalGenre: String?
    val originalStatus: Int
    // SY <--

    fun copyFrom(other: SManga) {
        // EXH -->
        if (other.title.isNotBlank() && originalTitle != other.title) {
            title = other.originalTitle
        }
        // EXH <--

        if (other.author != null) {
            author = /* SY --> */ other.originalAuthor // SY <--
        }

        if (other.artist != null) {
            artist = /* SY --> */ other.originalArtist // SY <--
        }

        if (other.description != null) {
            description = /* SY --> */ other.originalDescription // SY <--
        }

        if (other.genre != null) {
            genre = /* SY --> */ other.originalGenre // SY <--
        }

        if (other.thumbnail_url != null) {
            thumbnail_url = other.thumbnail_url
        }

        status = other.status

        update_strategy = other.update_strategy

        if (!initialized) {
            initialized = other.initialized
        }
    }

    fun copy() = create().also {
        it.url = url
        // SY -->
        it.title = originalTitle
        it.artist = originalArtist
        it.author = originalAuthor
        it.description = originalDescription
        it.genre = originalGenre
        it.status = originalStatus
        // SY <--
        it.thumbnail_url = thumbnail_url
        it.update_strategy = update_strategy
        it.initialized = initialized
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SManga {
            return SMangaImpl()
        }

        // SY -->
        operator fun invoke(
            url: String,
            title: String,
            artist: String? = null,
            author: String? = null,
            description: String? = null,
            genre: String? = null,
            status: Int = 0,
            thumbnail_url: String? = null,
            initialized: Boolean = false,
        ): SManga {
            return create().also {
                it.url = url
                it.title = title
                it.artist = artist
                it.author = author
                it.description = description
                it.genre = genre
                it.status = status
                it.thumbnail_url = thumbnail_url
                it.initialized = initialized
            }
        }
        // SY <--
    }
}

// SY -->
fun SManga.copy(
    url: String = this.url,
    title: String = this.originalTitle,
    artist: String? = this.originalArtist,
    author: String? = this.originalAuthor,
    description: String? = this.originalDescription,
    genre: String? = this.originalGenre,
    status: Int = this.status,
    thumbnail_url: String? = this.thumbnail_url,
    initialized: Boolean = this.initialized,
) = SManga.create().also {
    it.url = url
    it.title = title
    it.artist = artist
    it.author = author
    it.description = description
    it.genre = genre
    it.status = status
    it.thumbnail_url = thumbnail_url
    it.initialized = initialized
}
// SY <--
