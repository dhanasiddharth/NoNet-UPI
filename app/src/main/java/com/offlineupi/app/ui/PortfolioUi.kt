package com.offlineupi.app.ui

import com.offlineupi.app.portfolio.PortfolioAnalytics
import com.offlineupi.app.portfolio.PortfolioAnalytics.Bucket
import com.offlineupi.app.portfolio.PortfolioDb

/** Palette + snapshot access shared by the Money screens. */
object PortfolioUi {

    val bucketColors = mapOf(
        Bucket.India to 0xFF16A56A.toInt(), Bucket.US to 0xFF5B8DEF.toInt(),
        Bucket.Gold to 0xFFC8842E.toInt(), Bucket.Crypto to 0xFFA17AE0.toInt(),
    )

    /** Overview computes and caches; sub-screens reuse unless the cache is cold. */
    fun snapshot(db: PortfolioDb): PortfolioAnalytics.Snapshot? =
        PortfolioAnalytics.cached ?: PortfolioAnalytics.compute(db)
}
