// SPDX-License-Identifier: GPL-3.0-or-later
package com.patmanak.proton.andcalendarbridge.protonauth;

import me.proton.core.user.data.entity.AddressWithKeys;
import me.proton.core.user.data.extension.AddressMapperKt;
import me.proton.core.user.domain.entity.UserAddress;

/**
 * Narrow bridge to the pinned Core 36.8.0 Kotlin-internal/JVM-public local mapper.
 * Reuses Proton's protected-passphrase and key-flag mapping without a network store.
 * This dependency must be requalified when Core changes; see PROVENANCE.md.
 */
final class ProtonLocalAddressMapper {
    private ProtonLocalAddressMapper() {}

    static UserAddress map(AddressWithKeys address) {
        return AddressMapperKt.toUserAddress(address);
    }
}
