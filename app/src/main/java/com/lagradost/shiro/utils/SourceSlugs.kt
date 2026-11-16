package com.lagradost.shiro.utils

import com.lagradost.shiro.utils.aniyomi.AniyomiBridge
import com.lagradost.shiro.utils.cs3.CsBridge
import com.lagradost.shiro.utils.zg.ZangetsuBridge

/** True for slugs/tokens that belong to a source the user installed (CloudStream, Zangetsu, Aniyomi). */
fun isExtensionSlug(slug: String) =
    CsBridge.isCs3Slug(slug) || ZangetsuBridge.isZgSlug(slug) || AniyomiBridge.isAySlug(slug)
