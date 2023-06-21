package eu.kanade.presentation.manga

enum class DownloadAction {
    NEXT_1_CHAPTER,
    NEXT_5_CHAPTERS,
    NEXT_10_CHAPTERS,
    NEXT_25_CHAPTERS,
    UNREAD_CHAPTERS,
}

enum class EditCoverAction {
    EDIT,
    DELETE,
}

enum class MangaScreenItem {
    INFO_BOX,
    ACTION_ROW,

    // SY -->
    METADATA_INFO,

    // SY <--
    DESCRIPTION_WITH_TAG,

    // SY -->
    INFO_BUTTONS,
    CHAPTER_PREVIEW,

    // SY <--
    CHAPTER_HEADER,
    CHAPTER,
}
