package dev.treadtracker.app.widget

/** Layout breakpoints in dp; each launcher chooses its own cell sizes. */
internal object WidgetSizing {
    enum class Layout { TINY, COMPACT, REGULAR }

    fun layout(width: Float, height: Float): Layout = when {
        width < 105f || height < 60f -> Layout.TINY
        width < 145f || height < 100f -> Layout.COMPACT
        else -> Layout.REGULAR
    }

    fun textSize(width: Float, kind: Layout, digits: Int): Float {
        val availableWidth = (width - when (kind) {
            Layout.TINY -> 12f
            Layout.COMPACT -> 24f
            Layout.REGULAR -> 28f
        }).coerceAtLeast(28f)
        val limit = when (kind) {
            Layout.TINY -> 28f
            Layout.COMPACT -> 32f
            Layout.REGULAR -> 48f
        }
        return (availableWidth / (digits * 0.64f)).coerceIn(11f, limit)
    }
}
