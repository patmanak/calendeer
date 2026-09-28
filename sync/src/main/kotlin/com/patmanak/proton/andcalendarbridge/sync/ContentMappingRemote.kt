package com.patmanak.proton.andcalendarbridge.sync

/** Content interpretation can evolve without reinterpreting existing network cursors. */
interface ContentMappingRemote {
    val contentMapping: AdapterFormat
}
